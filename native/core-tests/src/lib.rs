// Exercise the actual storage and import implementations without a Windows GUI runtime.
#![allow(dead_code)]
#[path = "../../src/atomic_file.rs"]
mod atomic_file;
#[path = "../../src/backup.rs"]
pub mod backup;
#[path = "../../src/db.rs"]
mod db;
#[path = "../../src/images.rs"]
pub mod images;
#[path = "../../src/lan_wire.rs"]
mod lan_wire;

#[path = "../../src/lan_address.rs"]
mod lan_address;
