//! How many interfaces are online, for Settings' "N interface(s) active" (L83,
//! 2026-10-18). Until then Settings showed the enabled interface rows counted
//! once at start (StackRuntime), so it said 0 with RTNode Bluetooth up and kept
//! a backbone that had dropped.
//!
//! Transport tells its up- and down-edge listeners (`set_interface_online`, and
//! an online interface removed); at each edge the count is read again and handed
//! to the one sink, under one lock with the read, so two edges told on two
//! threads cannot hand Kotlin their counts out of order. Nothing here polls.

use std::sync::{Arc, Mutex, Once};

use reticulum_rust::transport::Transport;

/// Told (interfaces counted, AutoInterface peers online).
type Sink = Arc<dyn Fn(usize, usize) + Send + Sync>;

/// The sink, and the lock each read-and-tell holds.
static SINK: Mutex<Option<Sink>> = Mutex::new(None);

/// The interfaces Settings counts: every online one but the AutoInterface's
/// peers, which Transport lists each as an interface of its own
/// (`AutoInterfacePeer[if/addr]`) and which are the AutoInterface's, not more
/// interfaces.
pub fn counted(online: &[String]) -> usize {
    online.iter().filter(|name| !name.starts_with("AutoInterfacePeer[")).count()
}

/// The AutoInterface's peers online: other Reticulum devices it found on the
/// local network (Settings' "Local network" card, 2026-10-19).
pub fn auto_peers(online: &[String]) -> usize {
    online.iter().filter(|name| name.starts_with("AutoInterfacePeer[")).count()
}

/// Read the count and hand it to the sink, if there is one, under its lock.
fn tell() {
    if let Ok(slot) = SINK.lock() {
        if let Some(sink) = slot.as_ref() {
            let online = Transport::online_interface_names();
            sink(counted(&online), auto_peers(&online));
        }
    }
}

/// Tell `sink` the count now and at every edge from now on, in place of any
/// earlier sink. Transport can add a listener and never remove one, so its two
/// listeners are added once per process; they run on threads of their own,
/// never an interface's, so the sink may call into Kotlin.
pub fn set(sink: Sink) {
    static HOOKED: Once = Once::new();
    HOOKED.call_once(|| {
        Transport::add_interface_up_listener(Arc::new(|_: &str| tell()));
        Transport::add_interface_down_listener(Arc::new(|_: &str| tell()));
    });
    if let Ok(mut slot) = SINK.lock() {
        *slot = Some(sink);
    }
    tell();
}

/// Tell no one (the stack is stopping).
pub fn clear() {
    if let Ok(mut slot) = SINK.lock() {
        *slot = None;
    }
}

#[cfg(test)]
mod tests {
    use super::{auto_peers, counted};

    #[test]
    fn an_auto_interfaces_peers_are_not_counted_as_interfaces() {
        let online: Vec<String> = [
            "TCPInterface[Backbone/rns.example.org:4242]",
            "AutoInterface[Local]",
            "AutoInterfacePeer[wlan0/fe80::1]",
            "AutoInterfacePeer[wlan0/fe80::2]",
            "PrnsBLE[0a1b2c3d4e5f60718293a4b5c6d7e8f9]",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        // The backbone, the AutoInterface and the RTNode Bluetooth link.
        assert_eq!(counted(&online), 3);
        assert_eq!(counted(&[]), 0);
        // The two peers the AutoInterface found.
        assert_eq!(auto_peers(&online), 2);
        assert_eq!(auto_peers(&[]), 0);
    }
}
