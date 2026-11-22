use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::mpsc::Sender;
use std::thread;
use std::time::{Duration, Instant};
use crate::types::Node;

pub fn test_node_latency(address: &str, port: u16, timeout: Duration) -> Option<i64> {
    let target = format!("{address}:{port}");
    let addrs: Vec<SocketAddr> = target.to_socket_addrs().ok()?.collect();
    if addrs.is_empty() {
        return None;
    }

    let start = Instant::now();
    let sock = TcpStream::connect_timeout(&addrs[0], timeout).ok()?;
    drop(sock);
    let elapsed = start.elapsed().as_millis() as i64;
    Some(elapsed.max(1))
}

pub fn test_all_nodes(nodes: Vec<Node>, sender: Sender<(usize, Option<i64>)>) {
    thread::spawn(move || {
        let mut handles = Vec::new();

        for (idx, node) in nodes.into_iter().enumerate() {
            let s = sender.clone();
            let handle = thread::spawn(move || {
                let lat = test_node_latency(&node.address, node.port, Duration::from_millis(2000));
                let _ = s.send((idx, lat));
            });
            handles.push(handle);
        }

        for h in handles {
            let _ = h.join();
        }
    });
}
