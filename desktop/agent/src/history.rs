//! Ring of recent clip hashes for echo suppression (protocol spec, §Loop/echo suppression).

use std::collections::VecDeque;
use std::time::{Duration, Instant};

const WINDOW: Duration = Duration::from_secs(120);
const CAP: usize = 64;

pub struct ClipHistory {
    entries: VecDeque<([u8; 16], Instant)>,
}

impl ClipHistory {
    pub fn new() -> Self {
        Self { entries: VecDeque::new() }
    }

    pub fn seen_recently(&mut self, hash: &[u8; 16]) -> bool {
        self.prune();
        self.entries.iter().any(|(h, _)| h == hash)
    }

    pub fn record(&mut self, hash: &[u8; 16]) {
        self.prune();
        self.entries.push_back((*hash, Instant::now()));
        if self.entries.len() > CAP {
            self.entries.pop_front();
        }
    }

    fn prune(&mut self) {
        while matches!(self.entries.front(), Some((_, at)) if at.elapsed() > WINDOW) {
            self.entries.pop_front();
        }
    }
}
