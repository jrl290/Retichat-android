//! What a device owes its distro (RFed-rust SPEC.md §17.12 "On the sending
//! device", §17.11): the pure parts of building and uploading it, kept apart
//! from the JNI wrappers in `lib.rs` so `cargo test` runs them without a JVM.
//!
//! The Kotlin outbox (`service/DistroOutbox.kt`) keeps what is owed and decides
//! when to upload; this module does only what Kotlin cannot:
//!
//! * [`set_channel_sync_fields`]: C's fields, the membership message of §17.12.
//!   The generic field setters write str and bool only, and 0xFC is an array.
//! * [`set_group_sync_json`]: G's fields, the group sync of §17.14, from the
//!   bridge's JSON, by `lxmf_rust::distro` (the format is LXMF-rust's).
//! * [`pack_message`]: pack and sign a message once. What Kotlin keeps is that
//!   message, never packed again.
//! * [`build_propagation_upload`]: the upload of a packed message, encrypted to
//!   its distro's key and stamped for the propagation node, as
//!   `LXMessage::pack` builds `propagation_packed` for a PROPAGATED message.
//!   Built anew for every upload from the same packed message. The legacy
//!   build: an entry owed before the distro sync proof, or one that could not
//!   be sealed.
//! * [`seal`] and [`build_sealed_upload`]: the distro sync proof (RFed SPEC
//!   §17.13, DISTRO-SYNC-PROOF-DESIGN.md §5): the message encrypted to its
//!   distro and signed by it once, when it is owed, and each upload of it
//!   built from those same bytes with a new stamp, carrying the claim only for
//!   the RFed node's own `lxmf.propagation` (§5.4). Thin wrappers over
//!   `lxmf_rust::distro`, which RFed verifies with.

use std::sync::{Arc, Mutex, Once};

use lxmf_rust::distro::{propagation_payload, sealed_upload, seal_for_sync, sync_transient_id, verify_sync_claim, SyncClaim};
use lxmf_rust::lx_message::LXMessage;
use lxmf_rust::lx_stamper::{generate_stamp, WORKBLOCK_EXPAND_ROUNDS_PN};
use reticulum_rust::destination::{Destination, DestinationType};
use reticulum_rust::identity::{self, Identity};
use reticulum_rust::transport::Transport;
use rmpv::Value;

/// Where a Reticulum interface's up-edge goes (James, 2026-10-06, DESIGN_PRINCIPLES
/// §3, what a device owes its distro). An owed upload whose packet could not
/// leave the device, because no interface could carry it, is not a failure and
/// goes when an interface comes back online; the Kotlin outbox
/// (`DistroOutbox.onInterfaceOnline`) is told here. The edge is Transport's own
/// (`Transport::set_interface_online` false to true, the one AppLinks reacts to
/// when it re-attempts its links), not Android's "network available".
///
/// Transport can add a listener and never remove one, so the one listener is
/// added once per process ([`hook_interface_up`]) and what it tells is set and
/// cleared here: the JNI registers at each start and clears at each stop.
pub struct InterfaceUpRelay {
    sink: Mutex<Option<Arc<dyn Fn(&str) + Send + Sync>>>,
}

impl InterfaceUpRelay {
    pub const fn new() -> Self {
        Self { sink: Mutex::new(None) }
    }

    /// Tell `sink` of every up-edge from now on, in place of any earlier one.
    pub fn set(&self, sink: Arc<dyn Fn(&str) + Send + Sync>) {
        if let Ok(mut slot) = self.sink.lock() {
            *slot = Some(sink);
        }
    }

    /// Tell no one.
    pub fn clear(&self) {
        if let Ok(mut slot) = self.sink.lock() {
            *slot = None;
        }
    }

    /// Hand the interface `name`'s up-edge to the sink, if there is one. The sink
    /// is cloned out of the lock, so what it does (a call into Kotlin) never holds it.
    pub fn tell(&self, name: &str) {
        let sink = self.sink.lock().ok().and_then(|slot| slot.clone());
        if let Some(sink) = sink {
            sink(name);
        }
    }
}

/// The relay the JNI sets and clears.
pub static INTERFACE_UP: InterfaceUpRelay = InterfaceUpRelay::new();

/// Subscribe [`INTERFACE_UP`] to Transport's interface up-edge, once per process.
/// Transport runs its listeners on a thread of their own, never on the
/// interface's, so the sink may call into Kotlin.
pub fn hook_interface_up() {
    static HOOKED: Once = Once::new();
    HOOKED.call_once(|| {
        Transport::add_interface_up_listener(Arc::new(|name: &str| INTERFACE_UP.tell(name)));
    });
}

/// `FIELD_TICKET`: an empty bin in C, so that clients older than §17.12 take C
/// for a delivery notification and drop it. It is no ticket.
const FIELD_TICKET: u8 = 0x0C;
const FIELD_CUSTOM_TYPE: u8 = 0xFB;
const FIELD_CUSTOM_DATA: u8 = 0xFC;
const FIELD_CUSTOM_META: u8 = 0xFD;

/// The `FIELD_CUSTOM_TYPE` of a membership message.
pub const DISTRO_CHANNEL_TYPE: &str = "rfed.distro.channel";

/// `at_ms` is bounded at 2^53 - 1 on every client (§17.12 "`at_ms` on the wire").
pub const MAX_AT_MS: u64 = (1u64 << 53) - 1;

/// LXMF's destination hash and source hash lengths, the signature, and so the
/// shortest packed message that has a payload at all.
const DEST_LEN: usize = 16;
const LXMF_HEADER_LEN: usize = 16 + 16 + 64;

/// A stamp cost above this is not one a node means: it would be mined for
/// ever. (Costs are leading-zero bits of a SHA-256: LXMF's own are below 40.)
const MAX_STAMP_COST: i64 = 255;

/// Write C's fields on `msg`, as §17.12's table gives them:
///
/// | field | value |
/// |---|---|
/// | 0x0C | an empty bin |
/// | 0xFB | the str `"rfed.distro.channel"` |
/// | 0xFC | the native array `[op, name, at_ms]`: `op` the str `"join"` or `"leave"`, `name` the channel's full name, `at_ms` an integer |
/// | 0xFD | the sending device's own `lxmf.delivery` address, 32 lowercase hex |
///
/// `at_ms` is a `Value::from(u64)`, which rmpv packs as uint 64 (0xcf) for any
/// time after 1970-02-19; the web writes int 64 (0xd3), and every reader takes
/// both (CHECK_THESE_THINGS_FIRST §11: the array is native, never a bin of
/// packed msgpack). No 0xD1: C is a message to one's own devices and carries no
/// Message Display Name.
pub fn set_channel_sync_fields(
    msg: &mut LXMessage,
    op: &str,
    name: &str,
    at_ms: u64,
    device_hex: &str,
) -> Result<(), String> {
    if op != "join" && op != "leave" {
        return Err(format!("a membership action is \"join\" or \"leave\", not {op:?}"));
    }
    if name.is_empty() {
        return Err("a membership message names its channel".to_string());
    }
    if at_ms > MAX_AT_MS {
        return Err(format!("at_ms {at_ms} is above 2^53 - 1"));
    }
    if device_hex.len() != 32 || !device_hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("0xFD is the sending device's own lxmf.delivery address, 32 hex characters".to_string());
    }
    msg.set_field(FIELD_TICKET, Value::Binary(Vec::new()));
    msg.set_field(FIELD_CUSTOM_TYPE, Value::String(DISTRO_CHANNEL_TYPE.into()));
    msg.set_field(
        FIELD_CUSTOM_DATA,
        Value::Array(vec![Value::String(op.into()), Value::String(name.into()), Value::from(at_ms)]),
    );
    msg.set_field(FIELD_CUSTOM_META, Value::String(device_hex.to_ascii_lowercase().into()));
    Ok(())
}

/// Write the group sync G's fields on `msg` from the bridge's JSON (RFed SPEC §17.14,
/// GROUPS-DISTRO-DESIGN.md §17.2): `lxmf_rust::distro::GroupSync::from_json`, then
/// `lxmf_rust::distro::set_group_sync_fields`, which writes 0x0C an empty bin, 0xFB
/// `"rfed.distro.group"`, 0xFC the op's native array and 0xFD `device_hex`, and refuses (the
/// message unchanged) what a receiver's form check would drop. The format lives in LXMF-rust
/// once, with its golden vectors; nothing of it is written here.
pub fn set_group_sync_json(msg: &mut LXMessage, sync_json: &str, d_hex: &str, device_hex: &str) -> Result<(), String> {
    let sync = lxmf_rust::distro::GroupSync::from_json(sync_json)?;
    lxmf_rust::distro::set_group_sync_fields(msg, &sync, d_hex, device_hex)
}

/// Pack and sign the message behind `msg` once, and return its packed bytes:
/// `dest(16) | src(16) | signature(64) | msgpack payload`. A message already
/// packed returns the bytes it was packed to; nothing is ever packed again, so
/// every upload of it is one and the same LXMF message (its hash does not
/// change). The message's hash is then `LXMessage::hash`.
pub fn pack_message(msg: &Arc<Mutex<LXMessage>>) -> Result<Vec<u8>, String> {
    let mut msg = msg.lock().map_err(|e| e.to_string())?;
    if msg.packed.is_none() {
        msg.pack(false)?;
    }
    msg.packed.clone().ok_or_else(|| "the message packed to nothing".to_string())
}

/// The stamp cost a propagation node asks of an upload, from its announce's
/// `app_data` (LXMF `get_outbound_propagation_cost`): the first of the stamp
/// cost array, element 5. An `Err` says why none is known: the node's announce
/// has not been heard, or carries no valid cost.
pub fn node_stamp_cost(app_data: Option<&[u8]>) -> Result<u32, String> {
    match lxmf_rust::lxmf::pn_stamp_cost_from_app_data(app_data) {
        Some(cost) if (0..=MAX_STAMP_COST).contains(&cost) => Ok(cost as u32),
        Some(cost) => Err(format!("the propagation node asks a stamp cost of {cost}, which is not one")),
        None => Err("the propagation node's stamp cost is not known: its announce has not been heard".to_string()),
    }
}

/// Build the propagation upload of `packed` (a message D packed and signed,
/// `dest | src | signature | payload`): the encrypted body for D's key
/// `distro_public_key`, stamped for the node at `stamp_cost`, and wrapped as the
/// node reads it, `msgpack([timestamp_f64, [lxmf_data]])` with
/// `lxmf_data = dest | encrypted | stamp(32)` (LXMF `LXMessage.pack`,
/// PROPAGATED). The LXMF message inside is `packed` unchanged; the encryption
/// uses a fresh ephemeral key and the stamp is mined anew each time, so no two
/// uploads of one message are the same bytes, and the node and the siblings tell
/// them apart by what they carry, not by how it was wrapped.
///
/// It is encrypted to `distro_public_key` and to no other key: the key must
/// produce `packed`'s own destination hash. A sent-copy still owed to a distro
/// the device has given up is built with that distro's key, never the one held
/// now. Fails, with nothing left, when the key is wrong or short, the
/// encryption fails, or the stamp cannot be mined.
pub fn build_propagation_upload(distro_public_key: &[u8], packed: &[u8], stamp_cost: u32) -> Result<Vec<u8>, String> {
    if packed.len() < LXMF_HEADER_LEN {
        return Err(format!("a packed LXMF message is at least {LXMF_HEADER_LEN} bytes, this is {}", packed.len()));
    }
    let recipient = Identity::from_public_key(distro_public_key)?;
    let destination = Destination::new_outbound(
        Some(recipient.clone()),
        DestinationType::Single,
        "lxmf".to_string(),
        vec!["delivery".to_string()],
    )?;
    let dest = &packed[..DEST_LEN];
    if destination.hash != dest {
        return Err("the distro's key does not produce the message's destination".to_string());
    }
    let encrypted = recipient.encrypt(&packed[DEST_LEN..])?;
    let mut lxmf_data = Vec::with_capacity(DEST_LEN + encrypted.len() + 32);
    lxmf_data.extend_from_slice(dest);
    lxmf_data.extend_from_slice(&encrypted);
    // The transient id is the hash of what the node stores, before the stamp.
    let transient_id = identity::full_hash(&lxmf_data);
    let (stamp, _value) = generate_stamp(&transient_id, stamp_cost, WORKBLOCK_EXPAND_ROUNDS_PN);
    let stamp = stamp.ok_or_else(|| format!("the propagation stamp (cost {stamp_cost}) could not be generated"))?;
    lxmf_data.extend_from_slice(&stamp);
    // `msgpack [timebase f64, [bin lxmf_data]]`, every value native
    // (CHECK_THESE_THINGS_FIRST §11): LXMF-rust's one builder, byte for byte
    // what this crate wrote by hand until the distro sync proof.
    Ok(propagation_payload(now_seconds(), &lxmf_data))
}

/// The length of a distro sync signature, which [`seal`] puts first.
pub const SYNC_SIG_LEN: usize = 64;

/// Seal the packed message `packed` (D's own, `dest = src = D`) for the
/// distro sync proof, once, when it is owed (RFed SPEC §17.13;
/// DISTRO-SYNC-PROOF-DESIGN.md §5.1): `sealed = D_hash | D.encrypt(packed[16..])`
/// and D's signature over `sync_signed_bytes(D_hash, SHA-256(sealed))`, by
/// `lxmf_rust::distro::seal_for_sync`. Returns `sig(64) | sealed`, which the
/// outbox keeps with the entry in the same write that owes it; every upload of
/// the entry carries these bytes, never encrypted again.
///
/// Fails, with nothing made, when `distro` has no private key, `packed` is not
/// addressed to it, or the signature does not validate. The outbox then owes
/// the entry without them, says so as an error, and builds it the legacy way.
pub fn seal(distro: &Identity, packed: &[u8]) -> Result<Vec<u8>, String> {
    let sealed = seal_for_sync(distro, packed)?;
    let mut out = Vec::with_capacity(SYNC_SIG_LEN + sealed.sealed.len());
    out.extend_from_slice(&sealed.sig);
    out.extend_from_slice(&sealed.sealed);
    Ok(out)
}

/// A sealed upload as built ([`build_sealed_upload`]).
#[derive(Debug)]
pub struct SealedUpload {
    /// The `lxmf.propagation` upload: three elements with the claim, two without.
    pub upload: Vec<u8>,
    /// Why the claim that was asked for is not in it: the stored signature is
    /// not D's over these sealed bytes. `None` when it carries the claim, or
    /// none was asked for.
    pub claim_refused: Option<String>,
}

/// Build one upload of a sealed message (DISTRO-SYNC-PROOF-DESIGN.md §5.2):
/// the sealed bytes as they were stored, never encrypted again, the PN stamp
/// mined anew over `SHA-256(sealed)` at the node's `stamp_cost`, and a fresh
/// timebase. With `with_claim` (the caller's gate: the node is the RFed node's
/// own `lxmf.propagation`, §5.4) the envelope's third element is the claim
/// `[id, distro_public_key, sig]`; without it, today's two-element envelope,
/// which LXMF's own Resource handler requires.
///
/// The sealed message is addressed to `distro_public_key`'s `lxmf.delivery`
/// and to no other, as the legacy build requires of its key. A claim RFed
/// would refuse is never sent: when the stored signature is not D's over these
/// bytes, the upload is built without it and [`SealedUpload::claim_refused`]
/// says why. The message still goes, and wakes its devices as it does today;
/// failing the build instead would leave the entry owed for ever. Fails, with
/// nothing left, when the key is not the sealed message's or the stamp cannot
/// be mined. Blocks while the stamp is mined.
pub fn build_sealed_upload(
    distro_public_key: &[u8],
    sealed: &[u8],
    sig: &[u8],
    with_claim: bool,
    stamp_cost: u32,
) -> Result<SealedUpload, String> {
    check_sealed_destination(distro_public_key, sealed)?;
    let (stamp, _value) = generate_stamp(&sync_transient_id(sealed), stamp_cost, WORKBLOCK_EXPAND_ROUNDS_PN);
    let stamp = stamp.ok_or_else(|| format!("the propagation stamp (cost {stamp_cost}) could not be generated"))?;
    sealed_upload_with(distro_public_key, sealed, sig, with_claim, &stamp, now_seconds())
}

/// [`build_sealed_upload`] for a given stamp and timebase (the golden vector's).
fn sealed_upload_with(
    distro_public_key: &[u8],
    sealed: &[u8],
    sig: &[u8],
    with_claim: bool,
    stamp: &[u8],
    timebase: f64,
) -> Result<SealedUpload, String> {
    check_sealed_destination(distro_public_key, sealed)?;
    let (claim, claim_refused) = if with_claim {
        match sync_claim(distro_public_key, sealed, sig) {
            Ok(claim) => (Some(claim), None),
            Err(why) => (None, Some(why)),
        }
    } else {
        (None, None)
    };
    let upload = sealed_upload(sealed, stamp, claim.as_ref(), timebase)?;
    Ok(SealedUpload { upload, claim_refused })
}

/// The claim for `sealed`, checked as RFed checks it (`verify_sync_claim`).
fn sync_claim(distro_public_key: &[u8], sealed: &[u8], sig: &[u8]) -> Result<SyncClaim, String> {
    let claim = SyncClaim::for_sealed(sealed, distro_public_key, sig)?;
    verify_sync_claim(&claim, &sync_transient_id(sealed), sealed).map_err(|why| why.to_string())?;
    Ok(claim)
}

/// `sealed` is `D_hash | encrypted`, and `D_hash` is the `lxmf.delivery` of
/// `distro_public_key`: the upload goes to its own distro and no other.
fn check_sealed_destination(distro_public_key: &[u8], sealed: &[u8]) -> Result<(), String> {
    if sealed.len() <= DEST_LEN {
        return Err(format!("a sealed message is longer than {DEST_LEN} bytes, this is {}", sealed.len()));
    }
    let recipient = Identity::from_public_key(distro_public_key)?;
    let destination = Destination::new_outbound(
        Some(recipient),
        DestinationType::Single,
        "lxmf".to_string(),
        vec!["delivery".to_string()],
    )?;
    if destination.hash != sealed[..DEST_LEN] {
        return Err("the distro's key does not produce the sealed message's destination".to_string());
    }
    Ok(())
}

fn now_seconds() -> f64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use lxmf_rust::distro::unwrap_blob;

    /// A distro identity, its `lxmf.delivery` hash and a message from it to itself.
    fn distro_message(content: &str) -> (Identity, Vec<u8>, LXMessage) {
        let distro = Identity::new(true);
        let source = Destination::new_inbound(
            Some(distro.clone()),
            DestinationType::Single,
            "lxmf".to_string(),
            vec!["delivery".to_string()],
        )
        .expect("source destination");
        let hash = source.hash.clone();
        let msg = LXMessage::new(
            None,
            Some(source),
            Some(content.as_bytes().to_vec()),
            Some(Vec::new()),
            None,
            Some(LXMessage::DIRECT),
            Some(hash.clone()),
            Some(hash.clone()),
            None,
            false,
        )
        .expect("message");
        (distro, hash, msg)
    }

    const DEVICE: &str = "0123456789abcdef0123456789abcdef";

    /// The fields map of C written out by hand from §17.12's table: fixmap of
    /// four, `0x0C: bin(0)`, `0xFB: str`, `0xFC: array of three with at_ms as
    /// uint 64`, `0xFD: str(32)`. Keys over 0x7f are uint 8, as rmpv writes them.
    fn expected_fields(op: &str, name: &str, at_ms: u64, device: &str) -> Vec<u8> {
        let mut out = vec![0x84];
        out.extend_from_slice(&[0x0c, 0xc4, 0x00]);
        out.extend_from_slice(&[0xcc, 0xfb, 0xa0 | DISTRO_CHANNEL_TYPE.len() as u8]);
        out.extend_from_slice(DISTRO_CHANNEL_TYPE.as_bytes());
        out.extend_from_slice(&[0xcc, 0xfc, 0x93]);
        out.push(0xa0 | op.len() as u8);
        out.extend_from_slice(op.as_bytes());
        assert!(name.len() < 32);
        out.push(0xa0 | name.len() as u8);
        out.extend_from_slice(name.as_bytes());
        out.push(0xcf);
        out.extend_from_slice(&at_ms.to_be_bytes());
        out.extend_from_slice(&[0xcc, 0xfd, 0xd9, device.len() as u8]);
        out.extend_from_slice(device.as_bytes());
        out
    }

    /// The fields map of a packed message: element 3 of the payload array, as bytes.
    fn fields_of(packed: &[u8]) -> Vec<u8> {
        let payload = &packed[LXMF_HEADER_LEN..];
        assert_eq!(payload[0], 0x94, "the payload is an array of four");
        let mut rd = &payload[1..];
        for _ in 0..3 {
            rmpv::decode::read_value_ref(&mut rd).unwrap();
        }
        let start = payload.len() - rd.len();
        rmpv::decode::read_value_ref(&mut rd).unwrap();
        payload[start..payload.len() - rd.len()].to_vec()
    }

    #[test]
    fn c_is_written_as_the_spec_table_gives_it() {
        let (_distro, _hash, mut msg) = distro_message("");
        set_channel_sync_fields(&mut msg, "join", "public.general", 1_767_225_600_000, DEVICE).unwrap();
        let msg = Arc::new(Mutex::new(msg));
        let packed = pack_message(&msg).unwrap();
        assert_eq!(
            fields_of(&packed),
            expected_fields("join", "public.general", 1_767_225_600_000, DEVICE),
            "0x0C an empty bin, 0xFB a str, 0xFC a NATIVE array of op, name and uint 64 at_ms, 0xFD a str"
        );
    }

    /// The bytes of C's fields for join, `public.general`, 2026-01-01 00:00 UTC and `DEVICE`, written out
    /// as hex. The same literal is pinned on the reading side (Kotlin `DistroChannelSyncTest`), so a
    /// writer and a reader that drift apart cannot both pass.
    const C_FIELDS_HEX: &str = "840cc400ccfbb3726665642e64697374726f2e6368616e6e656cccfc93a46a6f696eae7075626c69632e67656e6572616ccf0000019b76daa800ccfdd9203031323334353637383961626364656630313233343536373839616263646566";

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    #[test]
    fn c_is_exactly_these_bytes_which_the_phones_reader_pins_too() {
        let (_d, _h, mut msg) = distro_message("");
        set_channel_sync_fields(&mut msg, "join", "public.general", 1_767_225_600_000, DEVICE).unwrap();
        let packed = pack_message(&Arc::new(Mutex::new(msg))).unwrap();
        assert_eq!(hex(&fields_of(&packed)), C_FIELDS_HEX);
    }

    #[test]
    fn at_ms_goes_as_uint64_for_a_real_time_and_the_array_is_never_a_bin() {
        let (_d, _h, mut msg) = distro_message("");
        set_channel_sync_fields(&mut msg, "leave", "0123456789abcdef.team", 1_767_225_600_000, DEVICE).unwrap();
        let packed = pack_message(&Arc::new(Mutex::new(msg))).unwrap();
        let fields = fields_of(&packed);
        // 2026-01-01 00:00 UTC is cf 00 00 01 9b 76 da a8 00 (§17.12), the type rmpv writes.
        let at: &[u8] = &[0xcf, 0x00, 0x00, 0x01, 0x9b, 0x76, 0xda, 0xa8, 0x00];
        assert!(fields.windows(at.len()).any(|w| w == at));
        // The 0xFC value starts with the array head 0x93, never a bin head (0xc4..0xc6).
        let pos = fields.windows(2).position(|w| w == [0xcc, 0xfc]).unwrap();
        assert_eq!(fields[pos + 2], 0x93);
    }

    #[test]
    fn c_is_refused_when_it_is_malformed() {
        let (_d, _h, mut msg) = distro_message("");
        assert!(set_channel_sync_fields(&mut msg, "Join", "public.x", 1, DEVICE).is_err());
        assert!(set_channel_sync_fields(&mut msg, "join", "", 1, DEVICE).is_err());
        assert!(set_channel_sync_fields(&mut msg, "join", "public.x", MAX_AT_MS + 1, DEVICE).is_err());
        assert!(set_channel_sync_fields(&mut msg, "join", "public.x", 1, "xyz").is_err());
        assert!(set_channel_sync_fields(&mut msg, "join", "public.x", 1, &"g".repeat(32)).is_err());
        // Nothing was written by a refused call.
        assert!(!msg.get_fields().as_map().map(|m| m.iter().any(|(k, _)| k.as_u64() == Some(0xFC))).unwrap_or(false));
        // 2^53 - 1 is the last at_ms there is.
        assert!(set_channel_sync_fields(&mut msg, "join", "public.x", MAX_AT_MS, DEVICE).is_ok());
    }

    #[test]
    fn the_device_address_is_written_lowercase() {
        let (_d, _h, mut msg) = distro_message("");
        set_channel_sync_fields(&mut msg, "join", "public.x", 1, &DEVICE.to_ascii_uppercase()).unwrap();
        let packed = pack_message(&Arc::new(Mutex::new(msg))).unwrap();
        let fields = fields_of(&packed);
        assert!(fields.windows(32).any(|w| w == DEVICE.as_bytes()));
    }

    #[test]
    fn a_message_is_packed_once_and_every_call_returns_the_same_bytes() {
        let (_d, _h, mut msg) = distro_message("hello");
        set_channel_sync_fields(&mut msg, "join", "public.x", 5, DEVICE).unwrap();
        let msg = Arc::new(Mutex::new(msg));
        let first = pack_message(&msg).unwrap();
        let hash = msg.lock().unwrap().hash.clone().unwrap();
        let second = pack_message(&msg).unwrap();
        assert_eq!(first, second, "never packed again");
        assert_eq!(hash, msg.lock().unwrap().hash.clone().unwrap());
        // dest | src | signature | payload, and the hash is SHA-256 of dest | src | payload.
        let mut hashed = first[..32].to_vec();
        hashed.extend_from_slice(&first[LXMF_HEADER_LEN..]);
        assert_eq!(identity::full_hash(&hashed), hash);
    }

    /// A node's announce `app_data`: `[name, timebase, active, limit, sync limit, [cost, ...], {}]`.
    fn node_app_data(cost: i64) -> Vec<u8> {
        let value = Value::Array(vec![
            Value::Nil,
            Value::from(1_767_225_600),
            Value::Boolean(true),
            Value::from(256),
            Value::from(10_240),
            Value::Array(vec![Value::from(cost), Value::from(3), Value::from(18)]),
            Value::Map(Vec::new()),
        ]);
        let mut out = Vec::new();
        rmpv::encode::write_value(&mut out, &value).unwrap();
        out
    }

    #[test]
    fn the_nodes_stamp_cost_is_read_from_its_announce() {
        assert_eq!(node_stamp_cost(Some(&node_app_data(0))), Ok(0));
        assert_eq!(node_stamp_cost(Some(&node_app_data(16))), Ok(16));
        assert!(node_stamp_cost(None).unwrap_err().contains("not known"));
        assert!(node_stamp_cost(Some(&[])).is_err());
        // Not a propagation node's announce.
        assert!(node_stamp_cost(Some(&[0x90])).is_err());
        assert!(node_stamp_cost(Some(&node_app_data(1000))).unwrap_err().contains("not one"));
        assert!(node_stamp_cost(Some(&node_app_data(-1))).is_err());
    }

    /// The `lxmf_data` of an upload: `dest | encrypted | stamp(32)`, from its wrapper.
    fn lxmf_data_of(upload: &[u8]) -> (f64, Vec<u8>) {
        assert_eq!(upload[0], 0x92, "a native array of two");
        assert_eq!(upload[1], 0xcb, "an f64 timestamp");
        let ts = f64::from_bits(u64::from_be_bytes(upload[2..10].try_into().unwrap()));
        assert_eq!(upload[10], 0x91, "an array of one blob");
        let (len, start) = match upload[11] {
            0xc4 => (upload[12] as usize, 13),
            0xc5 => (u16::from_be_bytes([upload[12], upload[13]]) as usize, 14),
            0xc6 => (u32::from_be_bytes(upload[12..16].try_into().unwrap()) as usize, 16),
            other => panic!("a bin, not {other:#x}"),
        };
        assert_eq!(start + len, upload.len());
        (ts, upload[start..].to_vec())
    }

    #[test]
    fn an_upload_is_the_packed_message_encrypted_to_its_distro_and_stamped() {
        let (distro, hash, mut msg) = distro_message("");
        set_channel_sync_fields(&mut msg, "join", "public.general", 1_767_225_600_000, DEVICE).unwrap();
        let packed = pack_message(&Arc::new(Mutex::new(msg))).unwrap();
        let key = distro.get_public_key().unwrap();

        let cost = 6;
        let upload = build_propagation_upload(&key, &packed, cost).unwrap();
        let (ts, data) = lxmf_data_of(&upload);
        assert!(ts > 1_700_000_000.0);
        assert_eq!(&data[..16], &hash[..], "the node reads dest in the clear at offset 0");
        // The stamp is the last 32 bytes, valid for the node's cost over what precedes it.
        let valid = lxmf_rust::lx_stamper::validate_pn_stamp(&data, cost).expect("a valid stamp for the node's cost");
        assert_eq!(valid.1, data[..data.len() - 32].to_vec());

        // A sibling unwraps it with the distro key: the message inside is the one packed,
        // with a valid distro signature, and it passes for a delivery notification (§17.12).
        let mut holder = distro.clone();
        let unwrapped = unwrap_blob(&mut holder, &data[..data.len() - 32]).unwrap().expect("addressed to the distro");
        assert_eq!(unwrapped.source_hash, hash);
        assert!(unwrapped.signature_validated);
        assert!(unwrapped.is_delivery_notification);
        assert!(unwrapped.content.is_empty());
        assert_eq!(unwrapped.fields.as_deref(), Some(&expected_fields("join", "public.general", 1_767_225_600_000, DEVICE)[..]));
        assert_eq!((unwrapped.sent_to, unwrapped.sent_by), (None, None));
    }

    #[test]
    fn a_free_node_still_gets_a_stamp_and_two_uploads_differ_but_carry_one_message() {
        let (distro, hash, mut msg) = distro_message("sent text");
        let packed = pack_message(&Arc::new(Mutex::new(msg))).unwrap();
        let key = distro.get_public_key().unwrap();
        let a = build_propagation_upload(&key, &packed, 0).unwrap();
        let b = build_propagation_upload(&key, &packed, 0).unwrap();
        assert_ne!(a, b, "a fresh ephemeral key and a fresh stamp each time");
        let (_, da) = lxmf_data_of(&a);
        let (_, db) = lxmf_data_of(&b);
        assert_eq!(da.len(), db.len());
        assert!(da.len() > 16 + 32, "a stamp follows the body even at cost 0");
        let mut holder = distro.clone();
        for data in [&da, &db] {
            let m = unwrap_blob(&mut holder, &data[..data.len() - 32]).unwrap().unwrap();
            assert_eq!(m.content, "sent text");
            assert_eq!(m.source_hash, hash);
            assert!(m.signature_validated);
        }
    }

    #[test]
    fn an_upload_is_encrypted_to_the_key_it_is_given_and_to_no_other() {
        let (distro, _hash, msg) = distro_message("x");
        let packed = pack_message(&Arc::new(Mutex::new(msg))).unwrap();
        let other = Identity::new(true);
        // The key must produce the message's destination: a held distro that is not the owed one is refused.
        let err = build_propagation_upload(&other.get_public_key().unwrap(), &packed, 0).unwrap_err();
        assert!(err.contains("does not produce"), "{err}");
        assert!(build_propagation_upload(&[1, 2, 3], &packed, 0).is_err());
        assert!(build_propagation_upload(&distro.get_public_key().unwrap(), &packed[..40], 0).is_err());
        // The owed distro's own key works whatever else the device holds.
        assert!(build_propagation_upload(&distro.get_public_key().unwrap(), &packed, 0).is_ok());
    }

    #[test]
    fn the_wrapper_picks_the_smallest_bin_header() {
        for (len, header) in [(10usize, vec![0xc4, 10u8]), (255, vec![0xc4, 255]), (256, vec![0xc5, 1, 0]), (65_535, vec![0xc5, 255, 255]), (65_536, vec![0xc6, 0, 1, 0, 0])] {
            let data = vec![7u8; len];
            let payload = propagation_payload(1.5, &data);
            assert_eq!(&payload[11..11 + header.len()], &header[..], "len {len}");
            assert_eq!(payload.len(), 11 + header.len() + len);
        }
        let payload = propagation_payload(1_767_225_600.25, b"abc");
        // The timestamp is a float 64, the shape the node's msgpack reads as [f64, [bin]].
        let value = rmpv::decode::read_value(&mut &payload[..]).unwrap();
        assert_eq!(
            value,
            Value::Array(vec![Value::F64(1_767_225_600.25), Value::Array(vec![Value::Binary(b"abc".to_vec())])])
        );
    }

    /// The JNI relays the status byte of every app-link event as AppLinks reports it, and Kotlin's
    /// `RetichatBridge.AppLinkStatus` must be the same numbers: the states 0..4 and the event
    /// RECOVERED (5), which is how a STALE propagation link's recovery reaches the outbox.
    #[test]
    fn kotlins_app_link_statuses_are_app_links_own() {
        let kotlin = include_str!("../../../app/src/main/kotlin/com/newendian/retichat/bridge/RetichatBridge.kt");
        for (name, code) in [
            ("NONE", app_links::APP_LINK_NONE),
            ("PATH_REQUESTED", app_links::APP_LINK_PATH_REQUESTED),
            ("ESTABLISHING", app_links::APP_LINK_ESTABLISHING),
            ("ACTIVE", app_links::APP_LINK_ACTIVE),
            ("DISCONNECTED", app_links::APP_LINK_DISCONNECTED),
            ("RECOVERED", app_links::APP_LINK_RECOVERED),
        ] {
            let declaration = format!("const val {name:<14} = {code}");
            assert!(kotlin.contains(&declaration), "Kotlin lacks `{declaration}`");
        }
        assert_eq!(app_links::APP_LINK_RECOVERED, 5);
    }

    /// The up-edge Kotlin's outbox hears is Transport's own: a real false-to-true transition of a
    /// registered interface reaches the relay once, an interface that is already online or goes
    /// offline tells nothing, hooking twice adds no second listener, and a cleared relay tells no
    /// one. (James, 2026-10-06: an owed upload that never left the device goes when an interface
    /// comes back online.)
    #[test]
    fn an_interfaces_up_edge_reaches_the_relay_once_per_edge_and_a_cleared_relay_tells_no_one() {
        use reticulum_rust::transport::{InterfaceStub, InterfaceStubConfig};
        use std::sync::mpsc;
        use std::time::Duration;

        let name = "jni-up-edge-test";
        let mut config = InterfaceStubConfig::default();
        config.name = name.to_string();
        config.online = Some(false);
        config.out = true;
        config.mode = InterfaceStub::MODE_FULL;
        Transport::register_interface_stub_config(config);

        let (tx, rx) = mpsc::channel::<String>();
        let tx = Mutex::new(tx);
        INTERFACE_UP.set(Arc::new(move |up: &str| {
            if up == name {
                let _ = tx.lock().unwrap().send(up.to_string());
            }
        }));
        hook_interface_up();
        hook_interface_up(); // once per process: a second call adds no second listener

        Transport::set_interface_online(name, true);
        assert_eq!(rx.recv_timeout(Duration::from_secs(5)).as_deref(), Ok(name), "the up-edge is told with the interface's name");
        // A told edge would arrive at once; the bound only ends the wait.
        assert!(rx.recv_timeout(Duration::from_millis(300)).is_err(), "told once, not twice");

        Transport::set_interface_online(name, true); // already online: no edge
        Transport::set_interface_online(name, false); // a down-edge
        assert!(rx.recv_timeout(Duration::from_millis(300)).is_err(), "no up-edge, nothing told");

        Transport::set_interface_online(name, true);
        assert!(rx.recv_timeout(Duration::from_secs(5)).is_ok(), "each up-edge is told");

        INTERFACE_UP.clear();
        Transport::set_interface_online(name, false);
        Transport::set_interface_online(name, true);
        assert!(rx.recv_timeout(Duration::from_millis(300)).is_err(), "a cleared relay tells no one");
    }

    // ---- The distro sync proof (RFed SPEC §17.13; DISTRO-SYNC-PROOF-DESIGN.md §5, §13.1) ----

    /// A sent copy D packed to itself, as the outbox owes it.
    fn packed_sent_copy(content: &str) -> (Identity, Vec<u8>, Vec<u8>) {
        let (distro, hash, msg) = distro_message(content);
        let packed = pack_message(&Arc::new(Mutex::new(msg))).unwrap();
        (distro, hash, packed)
    }

    /// `sig | sealed`, split as Kotlin splits it.
    fn sealed_of(distro: &Identity, packed: &[u8]) -> (Vec<u8>, Vec<u8>) {
        let both = seal(distro, packed).expect("sealed");
        (both[..SYNC_SIG_LEN].to_vec(), both[SYNC_SIG_LEN..].to_vec())
    }

    fn decode(upload: &[u8]) -> Vec<Value> {
        let mut rest = upload;
        let value = rmpv::decode::read_value(&mut rest).expect("msgpack");
        assert!(rest.is_empty(), "one value, nothing after it");
        match value {
            Value::Array(items) => items,
            other => panic!("an array, not {other:?}"),
        }
    }

    /// The one `lxmf_data` of an upload's message list.
    fn only_lxmf_data(envelope: &[Value]) -> Vec<u8> {
        match &envelope[1] {
            Value::Array(list) if list.len() == 1 => match &list[0] {
                Value::Binary(data) => data.clone(),
                other => panic!("a bin, not {other:?}"),
            },
            other => panic!("an array of one message, not {other:?}"),
        }
    }

    #[test]
    fn a_message_is_sealed_once_to_its_distro_and_signed_by_it() {
        let (distro, hash, packed) = packed_sent_copy("sealed text");
        let (sig, sealed) = sealed_of(&distro, &packed);
        assert_eq!(sig.len(), 64);
        assert_eq!(&sealed[..16], &hash[..], "sealed = D_hash | D.encrypt(packed[16..])");
        // A sibling opens it with the distro key: the message packed, with a valid distro signature.
        let mut holder = distro.clone();
        let opened = unwrap_blob(&mut holder, &sealed).unwrap().expect("addressed to the distro");
        assert_eq!(opened.content, "sealed text");
        assert!(opened.signature_validated);
        // The sync signature is D's over the 65 signed bytes.
        let d_hash: [u8; 16] = sealed[..16].try_into().unwrap();
        let signed = lxmf_rust::distro::sync_signed_bytes(&d_hash, &sync_transient_id(&sealed));
        assert_eq!(&signed[..17], b"rfed.distro.sync\x01");
        assert!(distro.validate(&sig, &signed));
        // A public-only identity cannot sign: an Err, never a panic. Nor is another distro's message sealed.
        let public_only = Identity::from_public_key(&distro.get_public_key().unwrap()).unwrap();
        assert!(seal(&public_only, &packed).is_err());
        let (_other, _other_hash, foreign) = packed_sent_copy("not D's");
        assert!(seal(&distro, &foreign).unwrap_err().contains("not addressed to the distro"));
        assert!(seal(&distro, &packed[..40]).is_err());
    }

    #[test]
    fn with_the_claim_the_upload_has_three_native_elements_and_without_it_two() {
        let (distro, _hash, packed) = packed_sent_copy("three or two");
        let (sig, sealed) = sealed_of(&distro, &packed);
        let key = distro.get_public_key().unwrap();
        let cost = 6;

        // For the RFed node's own lxmf.propagation: [timebase, [lxmf_data], {"rfed.distro.sync": [claim]}].
        let proven = build_sealed_upload(&key, &sealed, &sig, true, cost).unwrap();
        assert_eq!(proven.claim_refused, None);
        assert_eq!(proven.upload[0], 0x93, "fixarray(3)");
        let envelope = decode(&proven.upload);
        assert_eq!(envelope.len(), 3);
        assert!(matches!(envelope[0], Value::F64(t) if t > 1_700_000_000.0));
        let data = only_lxmf_data(&envelope);
        assert_eq!(&data[..data.len() - 32], &sealed[..], "the stored sealed bytes, never encrypted again");
        let valid = lxmf_rust::lx_stamper::validate_pn_stamp(&data, cost).expect("a stamp over SHA-256(sealed) at the node's cost");
        assert_eq!(valid.0, sync_transient_id(&sealed).to_vec());
        // The extension: a map with the str key and a native array of [bin16, bin64, bin64], never a bin of msgpack.
        let Value::Map(map) = &envelope[2] else { panic!("a map, not {:?}", envelope[2]) };
        assert_eq!(map.len(), 1);
        assert_eq!(map[0].0, Value::String("rfed.distro.sync".into()));
        let id = sync_transient_id(&sealed)[..16].to_vec();
        assert_eq!(
            map[0].1,
            Value::Array(vec![Value::Array(vec![Value::Binary(id), Value::Binary(key.clone()), Value::Binary(sig.clone())])])
        );
        // RFed reads it with the same helpers and accepts it.
        let claims = lxmf_rust::distro::decode_sync_extension(&envelope[2], 1);
        assert_eq!((claims.by_id.len(), claims.malformed, claims.duplicate), (1, 0, 0));
        let claim = claims.by_id.values().next().unwrap();
        assert_eq!(verify_sync_claim(claim, &valid.0, &valid.1), Ok(()));

        // For an override: LXMF's two-element envelope, exactly as the legacy wrapper writes it.
        let plain = build_sealed_upload(&key, &sealed, &sig, false, cost).unwrap();
        assert_eq!(plain.claim_refused, None);
        assert_eq!(plain.upload[0], 0x92, "fixarray(2)");
        let envelope = decode(&plain.upload);
        assert_eq!(envelope.len(), 2);
        let Value::F64(timebase) = envelope[0] else { panic!("an f64 timebase") };
        let data = only_lxmf_data(&envelope);
        assert_eq!(&data[..data.len() - 32], &sealed[..]);
        assert_eq!(plain.upload, propagation_payload(timebase, &data));
    }

    /// The Android twin of retichat-ffi distro_outbox.rs:412 (DISTRO-SYNC-PROOF-DESIGN.md §13.1, iOS).
    #[test]
    fn two_builds_of_one_entry_share_the_sealed_bytes_and_differ_only_in_stamp_and_timebase() {
        let (distro, _hash, packed) = packed_sent_copy("built twice");
        let (sig, sealed) = sealed_of(&distro, &packed);
        let key = distro.get_public_key().unwrap();
        let a = decode(&build_sealed_upload(&key, &sealed, &sig, true, 0).unwrap().upload);
        let b = decode(&build_sealed_upload(&key, &sealed, &sig, true, 0).unwrap().upload);
        let (da, db) = (only_lxmf_data(&a), only_lxmf_data(&b));
        assert_eq!(da.len(), db.len());
        assert_eq!(&da[..da.len() - 32], &db[..db.len() - 32], "one sealed message");
        assert_eq!(sync_transient_id(&da[..da.len() - 32]), sync_transient_id(&db[..db.len() - 32]), "one transient id");
        assert_eq!(a[2], b[2], "the same claim");
        // The legacy build encrypts anew each time: two of its uploads share nothing but the message inside.
        let l1 = build_propagation_upload(&key, &packed, 0).unwrap();
        let l2 = build_propagation_upload(&key, &packed, 0).unwrap();
        let (_, l1) = lxmf_data_of(&l1);
        let (_, l2) = lxmf_data_of(&l2);
        assert_ne!(&l1[..l1.len() - 32], &l2[..l2.len() - 32]);
    }

    #[test]
    fn a_claim_rfed_would_refuse_is_never_sent_and_the_message_still_goes() {
        let (distro, _hash, packed) = packed_sent_copy("refused claim");
        let (sig, sealed) = sealed_of(&distro, &packed);
        let key = distro.get_public_key().unwrap();
        // A signature over another sealing of the same message (each sealing is encrypted anew).
        let (other_sig, _other_sealed) = sealed_of(&distro, &packed);
        // A device's signature over the right bytes.
        let device = Identity::new(true);
        let d_hash: [u8; 16] = sealed[..16].try_into().unwrap();
        let device_sig = device.sign(&lxmf_rust::distro::sync_signed_bytes(&d_hash, &sync_transient_id(&sealed)));
        for bad in [other_sig, device_sig, sig[..63].to_vec(), Vec::new()] {
            let built = build_sealed_upload(&key, &sealed, &bad, true, 0).unwrap();
            assert!(built.claim_refused.is_some(), "refused");
            let envelope = decode(&built.upload);
            assert_eq!(envelope.len(), 2, "no claim: LXMF's envelope, which wakes as today");
            let data = only_lxmf_data(&envelope);
            assert_eq!(&data[..data.len() - 32], &sealed[..]);
        }
        // The good one is carried.
        assert_eq!(decode(&build_sealed_upload(&key, &sealed, &sig, true, 0).unwrap().upload).len(), 3);
    }

    #[test]
    fn a_sealed_upload_goes_to_its_own_distro_and_no_other() {
        let (distro, _hash, packed) = packed_sent_copy("own distro");
        let (sig, sealed) = sealed_of(&distro, &packed);
        let other = Identity::new(true);
        for with_claim in [true, false] {
            let err = build_sealed_upload(&other.get_public_key().unwrap(), &sealed, &sig, with_claim, 0).unwrap_err();
            assert!(err.contains("does not produce"), "{err}");
            assert!(build_sealed_upload(&[1, 2, 3], &sealed, &sig, with_claim, 0).is_err());
            assert!(build_sealed_upload(&distro.get_public_key().unwrap(), &sealed[..16], &sig, with_claim, 0).is_err());
        }
    }

    fn unhex(s: &str) -> Vec<u8> {
        (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
    }

    /// LXMF-rust tests/distro_sync_vectors.json, the vector LXMF-rust, RFed and Retichat-js assert:
    /// the JNI's sealed build writes its envelope, with and without the claim, byte for byte.
    #[test]
    fn the_golden_vector_is_built_byte_for_byte() {
        let vector: serde_json::Value =
            serde_json::from_str(include_str!("../../../../LXMF-rust/tests/distro_sync_vectors.json")).unwrap();
        let field = |name: &str| unhex(vector[name].as_str().unwrap());
        let key = unhex(vector["distro"]["public_key_hex"].as_str().unwrap());
        let (sealed, sig, stamp) = (field("sealed_hex"), field("sig_hex"), field("stamp_hex"));
        let timebase = vector["timebase"].as_f64().unwrap();
        let proven = sealed_upload_with(&key, &sealed, &sig, true, &stamp, timebase).unwrap();
        assert_eq!(proven.claim_refused, None);
        assert_eq!(hex(&proven.upload), vector["envelope_hex"].as_str().unwrap());
        let plain = sealed_upload_with(&key, &sealed, &sig, false, &stamp, timebase).unwrap();
        assert_eq!(hex(&plain.upload), vector["envelope_legacy_hex"].as_str().unwrap());
        // Its private key seals its packed message: new ciphertext each time, but D's signature over it.
        let distro = Identity::from_bytes(&unhex(vector["distro"]["private_key_hex"].as_str().unwrap())).unwrap();
        let (sig2, sealed2) = sealed_of(&distro, &field("packed_hex"));
        assert_eq!(&sealed2[..16], &sealed[..16]);
        assert_eq!(decode(&sealed_upload_with(&key, &sealed2, &sig2, true, &stamp, timebase).unwrap().upload).len(), 3);
    }

    /// LXMF-rust's group sync vectors (RFed SPEC §17.14), the copy `group_status.rs` checks
    /// against LXMF-rust's own.
    fn group_vectors() -> serde_json::Value {
        serde_json::from_str(include_str!("../tests/data/distro_group_sync_vectors.json")).unwrap()
    }

    /// The vectors' D, its `lxmf.delivery` hash in hex, and a message from D to D at `timestamp`.
    fn vector_message(v: &serde_json::Value, timestamp: f64) -> (String, LXMessage) {
        let d = Identity::from_bytes(&unhex(v["distro"]["private_key_hex"].as_str().unwrap())).unwrap();
        let source = Destination::new_inbound(Some(d), DestinationType::Single, "lxmf".to_string(), vec!["delivery".to_string()]).unwrap();
        let hash = source.hash.clone();
        assert_eq!(hex(&hash), v["distro"]["lxmf_delivery_hash_hex"].as_str().unwrap());
        let mut msg = LXMessage::new(
            None, Some(source), Some(Vec::new()), Some(Vec::new()), None, Some(LXMessage::DIRECT),
            Some(hash.clone()), Some(hash.clone()), None, false,
        )
        .unwrap();
        msg.timestamp = Some(timestamp);
        (hex(&hash), msg)
    }

    /// RFed SPEC §17.14: each op of the vectors, written from its JSON on a message from D to D
    /// with the vector's timestamp, packs to the bytes Python packed and hashed.
    #[test]
    fn g_is_written_from_its_json_to_the_python_bytes() {
        let v = group_vectors();
        let device = v["device_hex"].as_str().unwrap();
        for op in v["ops"].as_array().unwrap() {
            let name = op["name"].as_str().unwrap();
            let (d_hex, mut msg) = vector_message(&v, op["timestamp"].as_f64().unwrap());
            set_group_sync_json(&mut msg, &op["sync"].to_string(), &d_hex, device).unwrap_or_else(|e| panic!("{name}: {e}"));
            let msg = Arc::new(Mutex::new(msg));
            let packed = pack_message(&msg).unwrap();
            assert_eq!(hex(&packed), op["packed_hex"].as_str().unwrap(), "{name}");
            assert_eq!(hex(&fields_of(&packed)), op["fields_hex"].as_str().unwrap(), "{name}");
            assert_eq!(hex(msg.lock().unwrap().hash.as_ref().unwrap()), op["hash_hex"].as_str().unwrap(), "{name}");
        }
    }

    #[test]
    fn g_is_refused_when_a_receiver_would_drop_it_and_the_message_is_unchanged() {
        let v = group_vectors();
        let (d_hex, mut msg) = vector_message(&v, 1.0);
        let leave = r#"{"op":"leave","group_id":"9f8e7d6c5b4a39281706f5e4d3c2b1a0","at_ms":5,"left":[]}"#;
        let stranger = r#"{"op":"create","group_id":"9f8e7d6c5b4a39281706f5e4d3c2b1a0","at_ms":5,"name":"n","members":"abababababababababababababababab","keys":[],"left":[]}"#;
        for (json, d, device, why) in [
            ("{", d_hex.as_str(), DEVICE, "not JSON"),
            (r#"{"op":"leave","group_id":"9f8e7d6c5b4a39281706f5e4d3c2b1a0","at_ms":5}"#, d_hex.as_str(), DEVICE, "left is missing"),
            (r#"{"op":"leave","group_id":"9f8e7d6c5b4a39281706f5e4d3c2b1a0","at_ms":9007199254740992,"left":[]}"#, d_hex.as_str(), DEVICE, "2^53"),
            (stranger, d_hex.as_str(), DEVICE, "does not name D"),
            (leave, "xyz", DEVICE, "d_hex"),
            (leave, d_hex.as_str(), "xyz", "0xFD"),
        ] {
            let err = set_group_sync_json(&mut msg, json, d, device).expect_err(why);
            assert!(err.contains(why), "{why}: {err}");
        }
        assert_eq!(msg.fields, Value::Map(Vec::new()), "a refused call wrote nothing");
        let (_other, _hash, mut not_d) = distro_message("");
        assert!(set_group_sync_json(&mut not_d, leave, &d_hex, DEVICE).unwrap_err().contains("from D to D"));
    }
}
