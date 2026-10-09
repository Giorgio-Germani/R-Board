//! Bluetooth Classic RFCOMM client transport (Windows, via WinRT).
//! The phone (REVENTOR keyboard) is the RFCOMM server; we are the client.

use std::io;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use windows::core::*;
use windows::Devices::Bluetooth::Rfcomm::{RfcommDeviceService, RfcommServiceId};
use windows::Devices::Bluetooth::{BluetoothCacheMode, BluetoothDevice};
use windows::Devices::Enumeration::DeviceInformation;
use windows::Networking::Sockets::{SocketProtectionLevel, StreamSocket};
use windows::Storage::Streams::{DataReader, DataWriter};

use windows_future::AsyncStatus;

use protocol::Frame;

pub const SERVICE_UUID: GUID = GUID::from_u128(0x8c1f9a52_6e3b_4c7a_9d4e_2b1f0a7c5d31);

/// Blocks until the WinRT async operation completes (windows-future 0.3 keeps
/// its blocking helpers private, so we poll Status).
pub(crate) trait Wait {
    type Output;
    fn rwait(&self) -> Result<Self::Output>;
}

impl<T: RuntimeType> Wait for windows_future::IAsyncOperation<T> {
    type Output = T;
    fn rwait(&self) -> Result<T> {
        loop {
            match self.Status()? {
                AsyncStatus::Started => thread::sleep(Duration::from_millis(2)),
                AsyncStatus::Completed => return self.GetResults(),
                AsyncStatus::Error => {
                    return Err(Error::new(self.ErrorCode()?, "async operation failed"))
                }
                _ => return Err(Error::new(self.ErrorCode()?, "async operation canceled")),
            }
        }
    }
}

impl Wait for windows_future::IAsyncAction {
    type Output = ();
    fn rwait(&self) -> Result<()> {
        loop {
            match self.Status()? {
                AsyncStatus::Started => thread::sleep(Duration::from_millis(2)),
                AsyncStatus::Completed => return Ok(()),
                AsyncStatus::Error => {
                    return Err(Error::new(self.ErrorCode()?, "async operation failed"))
                }
                _ => return Err(Error::new(self.ErrorCode()?, "async operation canceled")),
            }
        }
    }
}

pub struct Connection {
    socket: StreamSocket,
    writer: Arc<Mutex<DataWriter>>,
    reader: DataReader,
}

/// Blocking `impl Read` adapter over the WinRT DataReader.
struct ReaderSource<'a> {
    reader: &'a DataReader,
}

impl io::Read for ReaderSource<'_> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        let loaded =
            self.reader.LoadAsync(buf.len() as u32).map_err(win_err)?.rwait().map_err(win_err)? as usize;
        if loaded == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "socket closed"));
        }
        let mut tmp = vec![0u8; loaded];
        self.reader.ReadBytes(&mut tmp).map_err(win_err)?;
        buf[..loaded].copy_from_slice(&tmp);
        Ok(loaded)
    }
}

fn win_err(e: Error) -> io::Error {
    io::Error::other(format!("{e}"))
}

/// `AA:BB:CC:DD:EE:FF` → u64 bluetooth address.
pub fn parse_address(s: &str) -> Option<u64> {
    let hex: String = s.split([':', '-']).collect();
    if hex.len() != 12 {
        return None;
    }
    u64::from_str_radix(&hex, 16).ok()
}

pub fn hostname() -> String {
    std::env::var("COMPUTERNAME").unwrap_or_else(|_| "windows-pc".into())
}

/// human-readable name of a paired device (shown by the setup wizard)
pub fn device_name(address: u64) -> Option<String> {
    let device = BluetoothDevice::FromBluetoothAddressAsync(address).ok()?.rwait().ok()?;
    let name = device.Name().ok()?.to_string();
    let name = name.trim().to_string();
    if name.is_empty() { None } else { Some(name) }
}

/// Does the phone at `address` advertise the clipboard sync service right
/// now? Uncached SDP query, retried (the query is flaky, see
/// `discover_paired_phone`); a `false` usually means the phone-side sync
/// service is not running.
pub fn service_available(address: u64) -> Result<bool> {
    let device = BluetoothDevice::FromBluetoothAddressAsync(address)?.rwait()?;
    let service_id = RfcommServiceId::FromUuid(SERVICE_UUID)?;
    for attempt in 1..=3 {
        let services = device
            .GetRfcommServicesForIdWithCacheModeAsync(&service_id, BluetoothCacheMode::Uncached)?
            .rwait()?;
        if services.Services()?.Size()? > 0 {
            return Ok(true);
        }
        if attempt < 3 {
            thread::sleep(Duration::from_millis(1500));
        }
    }
    Ok(false)
}

/// Scans the paired Bluetooth devices for one advertising the R-Board sync
/// service and returns its address. Slow (uncached SDP query per device).
/// The uncached SDP query is flaky (sometimes returns empty, 0x80070490 on
/// connect), so every device is queried up to three times and everything is
/// logged to make remote diagnosis possible.
pub fn discover_paired_phone() -> Result<Option<u64>> {
    let selector = BluetoothDevice::GetDeviceSelectorFromPairingState(true)?;
    let devices = DeviceInformation::FindAllAsyncAqsFilter(&selector)?.rwait()?;
    let count = devices.Size()?;
    crate::logln(&format!("search: {count} paired Bluetooth device(s) found"));
    for i in 0..count {
        let info = devices.GetAt(i)?;
        let name = info.Name().unwrap_or_default().to_string();
        let device = match BluetoothDevice::FromIdAsync(&info.Id()?)?.rwait() {
            Ok(d) => d,
            Err(e) => {
                crate::logln(&format!("search: {name}: gone while querying ({e})"));
                continue; // device gone between enumeration and query
            }
        };
        let address = device.BluetoothAddress()?;
        let service_id = RfcommServiceId::FromUuid(SERVICE_UUID)?;
        for attempt in 1..=3 {
            let services = device
                .GetRfcommServicesForIdWithCacheModeAsync(&service_id, BluetoothCacheMode::Uncached)?
                .rwait()?;
            let found = services.Services()?.Size()?;
            if found > 0 {
                crate::logln(&format!("search: {name} advertises the sync service"));
                return Ok(Some(address));
            }
            if attempt < 3 {
                thread::sleep(Duration::from_millis(1500));
            } else {
                crate::logln(&format!("search: {name} ({address:012X}): no sync service after 3 queries"));
            }
        }
    }
    Ok(None)
}

impl Connection {
    pub fn connect(address: u64) -> Result<Self> {
        let device = BluetoothDevice::FromBluetoothAddressAsync(address)?.rwait()?;
        let service_id = RfcommServiceId::FromUuid(SERVICE_UUID)?;
        // Uncached: Windows caches RFCOMM services discovered at pairing time;
        // the phone's service may have started after pairing
        let services = device
            .GetRfcommServicesForIdWithCacheModeAsync(&service_id, BluetoothCacheMode::Uncached)?
            .rwait()?;
        let list = services.Services()?;
        if list.Size()? == 0 {
            return Err(Error::new(
                HRESULT::from_win32(1168), // ERROR_NOT_FOUND
                "phone does not advertise the R-Board clipboard sync service (app running? paired? on BT?)",
            ));
        }
        let service: RfcommDeviceService = list.GetAt(0)?;
        let host = service.ConnectionHostName()?;
        let name = service.ConnectionServiceName()?;
        let socket = StreamSocket::new()?;
        socket.ConnectAsync(&host, &name)?.rwait()?;
        let _ = SocketProtectionLevel::BluetoothEncryptionWithAuthentication; // enforced by the RFCOMM service
        let out = socket.OutputStream()?;
        let input = socket.InputStream()?;
        let writer = DataWriter::CreateDataWriter(&out)?;
        let reader = DataReader::CreateDataReader(&input)?;
        Ok(Self {
            socket,
            writer: Arc::new(Mutex::new(writer)),
            reader,
        })
    }

    /// Closes the socket so a blocked reader wakes up (used on session teardown).
    pub fn shutdown(&self) {
        let _ = self.socket.Close();
    }

    pub fn write_frame(&self, frame: &Frame) -> io::Result<()> {
        let mut bytes = Vec::new();
        protocol::write_frame(&mut bytes, frame)?;
        let writer = self.writer.lock().map_err(|_| io::Error::other("writer poisoned"))?;
        writer.WriteBytes(&bytes).map_err(win_err)?;
        writer.StoreAsync().map_err(win_err)?.rwait().map_err(win_err)?;
        Ok(())
    }

    /// Blocks until a full frame arrives. Returns Ok(None) on clean EOF.
    pub fn read_frame(&self) -> io::Result<Option<Frame>> {
        let mut source = ReaderSource { reader: &self.reader };
        protocol::read_frame(&mut source)
    }
}
