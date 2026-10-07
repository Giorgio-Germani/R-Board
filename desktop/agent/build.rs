fn main() {
    let mut res = winres::WindowsResource::new();
    res.set_icon("icon.ico");
    let _ = res.compile();
}
