//! JNI bridge for Retichat Android.
//!
//! This crate produces a `cdylib` (`libretichat_jni.so`) loaded via
//! `System.loadLibrary("retichat_jni")` in Kotlin.
//!
//! Every public function follows the JNI naming convention:
//!   Java_com_newendian_retichat_bridge_RetichatBridge_<methodName>
//!
//! ## Handle conventions
//!
//! Opaque `u64` handles are passed as `jlong` across the JNI boundary.
//! A return value of `0` indicates an error – call `nativeLastError()` to
//! retrieve the message.
//!
//! ## Thread safety
//!
//! The Rust-side handle registry and all `Arc<Mutex<_>>` wrappers are
//! thread-safe.  The delivery callback is dispatched from a Rust background
//! thread, so it attaches to the JVM before calling Kotlin.

mod distro_outbox;
mod group_status;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JObjectArray, JString, JValue};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};

use lxmf_rust::ffi as lxmf;
use reticulum_rust::destination::{Destination, DestinationType};
use reticulum_rust::ffi as rns;
use reticulum_rust::identity::Identity;
use reticulum_rust::lxstamper::LXStamper;
use reticulum_rust::packet::Packet;
use reticulum_rust::transport::Transport;

// ---------------------------------------------------------------------------
// Android logcat output
// ---------------------------------------------------------------------------
extern "C" {
    fn __android_log_write(prio: i32, tag: *const u8, text: *const u8) -> i32;
}

/// Write a message to Android logcat under the "RNS" tag (priority = INFO = 4).
fn android_log(msg: &str) {
    let tag = b"RNS\0";
    let mut buf = msg.as_bytes().to_vec();
    buf.push(0); // null-terminate
    unsafe {
        __android_log_write(4, tag.as_ptr(), buf.as_ptr());
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Convert a Rust `Result` into a JNI handle (0 on error, sets last-error).
fn ok_or_zero(r: Result<u64, String>) -> jlong {
    match r {
        Ok(h) => h as jlong,
        Err(e) => {
            rns::set_error(e);
            0
        }
    }
}

/// Convert a Rust `Result<(), String>` into a JNI int (0 ok, -1 error).
fn ok_or_neg(r: Result<(), String>) -> jint {
    match r {
        Ok(()) => 0,
        Err(e) => {
            rns::set_error(e);
            -1
        }
    }
}

/// Extract a Rust `String` from a JNI `JString`.
fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|js| js.into())
        .unwrap_or_default()
}

fn parse_destination_aspects(app: &str, aspects: &str) -> Vec<String> {
    let normalized_app = app.trim();
    let mut parsed: Vec<String> = aspects
        .split(|c| c == '.' || c == ',')
        .map(str::trim)
        .filter(|segment| !segment.is_empty())
        .map(|segment| segment.to_string())
        .collect();

    if parsed.first().map(|segment| segment.as_str()) == Some(normalized_app) {
        parsed.remove(0);
    }

    parsed
}

/// Extract a `Vec<u8>` from a JNI byte array.
fn jbytes_to_vec(env: &JNIEnv, arr: &JByteArray) -> Vec<u8> {
    env.convert_byte_array(arr).unwrap_or_default()
}

/// Create a JNI byte array from a `&[u8]`.
fn vec_to_jbytes(env: &JNIEnv, data: &[u8]) -> jbyteArray {
    let out = env.new_byte_array(data.len() as i32).unwrap();
    let _ = env.set_byte_array_region(&out, 0, unsafe {
        std::slice::from_raw_parts(data.as_ptr() as *const i8, data.len())
    });
    out.into_raw()
}

// ---------------------------------------------------------------------------
// Stored callback (delivery)
// ---------------------------------------------------------------------------

static DELIVERY_CB: Mutex<Option<(JavaVM, GlobalRef)>> = Mutex::new(None);
static ANNOUNCE_CB: Mutex<Option<(JavaVM, GlobalRef)>> = Mutex::new(None);
static MESSAGE_STATE_CB: Mutex<Option<(JavaVM, GlobalRef)>> = Mutex::new(None);

/// Process-wide APP_LINK status callback (mirrors iOS
/// `lxmf_app_link_register_status_callback`).  Only one callback is
/// supported on the Kotlin side; replacing it overwrites the prior ref.
static APP_LINK_STATUS_CB: Mutex<Option<(JavaVM, GlobalRef)>> = Mutex::new(None);

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeInit(configDir: String, logLevel: Int): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    config_dir: JString,
    log_level: jint,
) -> jint {
    android_log("nativeInit: setting log callback");
    // Route Rust log() output to Android logcat before init
    rns::set_log_callback(|msg| {
        android_log(&msg);
    });
    android_log("nativeInit: log callback set, calling init");

    let dir = jstring_to_string(&mut env, &config_dir);
    let result = rns::init(&dir, log_level);
    android_log(&format!("nativeInit: init result={:?}", result));

    // Re-apply log callback after init in case init() reset LOG_STATE
    rns::set_log_callback(|msg| {
        android_log(&msg);
    });
    android_log("nativeInit: log callback re-applied after init");

    ok_or_neg(result)
}

/// `RetichatBridge.nativeShutdown(): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeShutdown(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    // The whole stack, not just Reticulum: the app-link and runtime-link
    // registries are process-global and a restart in this process must not
    // inherit links whose interfaces are gone (2026-09-24).
    ok_or_neg(lxmf::shutdown())
}

/// `RetichatBridge.nativeLastError(): String?`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeLastError(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match rns::take_error() {
        Some(msg) => env
            .new_string(&msg)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

// ---- Identity ----

/// `RetichatBridge.nativeIdentityCreate(): Long`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeIdentityCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    ok_or_zero(rns::identity_create())
}

/// `RetichatBridge.nativeIdentityFromFile(path: String): Long`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeIdentityFromFile(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    let p = jstring_to_string(&mut env, &path);
    ok_or_zero(rns::identity_from_file(&p))
}

/// `RetichatBridge.nativeIdentityFromBytes(bytes: ByteArray): Long`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeIdentityFromBytes(
    env: JNIEnv,
    _class: JClass,
    bytes: JByteArray,
) -> jlong {
    let b = jbytes_to_vec(&env, &bytes);
    ok_or_zero(rns::identity_from_bytes(&b))
}

/// `RetichatBridge.nativeIdentityToFile(handle: Long, path: String): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeIdentityToFile(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path: JString,
) -> jint {
    let p = jstring_to_string(&mut env, &path);
    ok_or_neg(rns::identity_to_file(handle as u64, &p))
}

/// `RetichatBridge.nativeIdentityPublicKey(handle: Long): ByteArray?`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeIdentityPublicKey(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    match rns::identity_public_key(handle as u64) {
        Ok(bytes) => vec_to_jbytes(&env, &bytes),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

/// `RetichatBridge.nativeIdentityHash(handle: Long): ByteArray?`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeIdentityHash(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    match rns::identity_hash(handle as u64) {
        Ok(bytes) => vec_to_jbytes(&env, &bytes),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

/// `RetichatBridge.nativeIdentityDestroy(handle: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeIdentityDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    ok_or_neg(rns::identity_destroy(handle as u64))
}

// ---- Destination ----

/// `RetichatBridge.nativeDestinationHash(idHandle: Long, appName: String, aspects: String): ByteArray?`
///
/// `aspects` may be dot- or comma-separated, e.g. `"delivery"` or
/// `"channel.subscribe"`.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeDestinationHash(
    mut env: JNIEnv,
    _class: JClass,
    id_handle: jlong,
    app_name: JString,
    aspects: JString,
) -> jbyteArray {
    let app = jstring_to_string(&mut env, &app_name);
    let asp = jstring_to_string(&mut env, &aspects);
    let parts_owned = parse_destination_aspects(&app, &asp);
    let parts: Vec<&str> = parts_owned.iter().map(|segment| segment.as_str()).collect();

    match rns::destination_hash_for(id_handle as u64, &app, &parts) {
        Ok(h) => vec_to_jbytes(&env, &h),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

// ---- Transport ----

/// `RetichatBridge.nativeTransportHasPath(destHash: ByteArray): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeTransportHasPath(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    if rns::transport_has_path(&h) { 1 } else { 0 }
}

/// `RetichatBridge.nativeTransportIsPathVerifiedThisSession(destHash: ByteArray): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeTransportIsPathVerifiedThisSession(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    if rns::transport_is_path_verified_this_session(&h) { 1 } else { 0 }
}

/// `RetichatBridge.nativeTransportIdentityKnown(destHash: ByteArray): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeTransportIdentityKnown(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    if rns::identity_known(&h) { 1 } else { 0 }
}

/// `RetichatBridge.nativeTransportRequestPath(destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeTransportRequestPath(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(rns::transport_request_path(&h))
}

#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeIdentityRememberLxmfDelivery(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
    public_key: JByteArray,
) -> jint {
    let claimed_hash = jbytes_to_vec(&env, &dest_hash);
    let public_key = jbytes_to_vec(&env, &public_key);
    let identity = match Identity::from_public_key(&public_key) {
        Ok(identity) => identity,
        Err(error) => { rns::set_error(error); return -1; }
    };
    let destination = match Destination::new_outbound(
        Some(identity), DestinationType::Single, "lxmf".into(), vec!["delivery".into()],
    ) {
        Ok(destination) => destination,
        Err(error) => { rns::set_error(error); return -1; }
    };
    if destination.hash != claimed_hash {
        rns::set_error("public key does not match claimed lxmf.delivery hash".into());
        return -1;
    }
    ok_or_neg(Identity::remember_destination(&claimed_hash, &public_key, None))
}

/// `RetichatBridge.nativeTransportClonePathAndIdentity(sourceHash, destHash): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeTransportClonePathAndIdentity(
    env: JNIEnv,
    _class: JClass,
    source_hash: JByteArray,
    dest_hash: JByteArray,
) -> jint {
    let source = jbytes_to_vec(&env, &source_hash);
    let dest = jbytes_to_vec(&env, &dest_hash);
    if source.is_empty() || dest.is_empty() || source == dest {
        return 0;
    }

    if !Transport::clone_path(&source, &dest) {
        return 0;
    }

    if let Some(public_key) = Identity::recall_public_key(&source) {
        let _ = Identity::remember_destination(&dest, &public_key, None);
    }

    1
}

/// `RetichatBridge.nativeTransportHopsTo(destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeTransportHopsTo(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    rns::transport_hops_to(&h)
}

// ---- Router ----

/// `RetichatBridge.nativeRouterCreate(identityHandle: Long, storagePath: String): Long`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterCreate(
    mut env: JNIEnv,
    _class: JClass,
    identity_handle: jlong,
    storage_path: JString,
) -> jlong {
    let sp = jstring_to_string(&mut env, &storage_path);
    ok_or_zero(lxmf::router_create(identity_handle as u64, &sp))
}

/// `RetichatBridge.nativeRouterRegisterDelivery(router: Long, identity: Long, name: String, stampCost: Int): Long`
///
/// `name` is the initial Message Display Name (DISPLAY_NAMES.md §4.1), never
/// announced; "" = none. Change it with `nativeRouterSetMessageDisplayName`.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterRegisterDelivery(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    identity: jlong,
    name: JString,
    stamp_cost: jint,
) -> jlong {
    let n = jstring_to_string(&mut env, &name);
    let cost = if stamp_cost < 0 { None } else { Some(stamp_cost as u32) };
    let display = if n.is_empty() { None } else { Some(n.as_str()) };
    ok_or_zero(lxmf::router_register_delivery(router as u64, identity as u64, display, cost))
}

/// An optional Kotlin `String?` as UTF-8; null or "" is `None`.
fn optional_jstring(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    let v = jstring_to_string(env, s);
    if v.is_empty() { None } else { Some(v) }
}

/// `RetichatBridge.nativeRouterSetMessageDisplayName(router: Long, name: String?): Int`
///
/// DISPLAY_NAMES.md §4.1: the Message Display Name the router adds (key 0
/// of the Retichat field 0xD1) to outbound messages by the name-ledger rule. null or "" clears it;
/// the name is cleaned (§3). Takes effect at once, no restart. 0 / -1.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterSetMessageDisplayName(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    name: JString,
) -> jint {
    let name = optional_jstring(&mut env, &name);
    ok_or_neg(lxmf::router_set_message_display_name(router as u64, name.as_deref()).map(|_| ()))
}

/// `RetichatBridge.nativeRouterSetAnnounceDisplayName(router: Long, name: String?): Int`
///
/// DISPLAY_NAMES.md §2.2: the PUBLIC name in the lxmf.delivery announce.
/// null or "" (the default) announces nil. Cleaned with the announce rules
/// ("Anonymous Peer" is none); the next announce carries it. 0 / -1.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterSetAnnounceDisplayName(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    name: JString,
) -> jint {
    let name = optional_jstring(&mut env, &name);
    ok_or_neg(lxmf::router_set_announce_display_name(router as u64, name.as_deref()).map(|_| ()))
}

/// `RetichatBridge.nativeDisplayNameClean(raw: ByteArray, announce: Boolean): String?`
///
/// DISPLAY_NAMES.md §3, for the settings screens: the name exactly as the
/// router will send it, or null when it cleans to no name. `announce` also
/// maps "Anonymous Peer" to null.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeDisplayNameClean(
    env: JNIEnv,
    _class: JClass,
    raw: JByteArray,
    announce: jni::sys::jboolean,
) -> jstring {
    let bytes = if raw.is_null() { Vec::new() } else { jbytes_to_vec(&env, &raw) };
    let cleaned = if announce != 0 {
        lxmf_rust::display_name::clean_announce(&bytes)
    } else {
        lxmf_rust::display_name::clean(&bytes)
    };
    match cleaned {
        Some(name) => match env.new_string(name) {
            Ok(s) => s.into_raw(),
            Err(e) => {
                rns::set_error(format!("jstring: {e}"));
                std::ptr::null_mut()
            }
        },
        None => std::ptr::null_mut(),
    }
}

/// `RetichatBridge.nativeDisplayNameDecode(fieldsRaw: ByteArray): ByteArray?`
///
/// The name, key 0 of the Retichat field 0xD1 ({0xD1: {0: name}}; a 0xD1
/// that is not a map is absent), from the msgpack `fields` a delivery
/// callback hands over:
/// `[name_state u8 (0 absent, 1 clear, 2 name) | name_len u16 BE | name]`,
/// always at least 3 bytes. Accepting it depends on the message's
/// signature (DISPLAY_NAMES.md §5.2).
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeDisplayNameDecode(
    env: JNIEnv,
    _class: JClass,
    fields_raw: JByteArray,
) -> jbyteArray {
    let bytes = if fields_raw.is_null() { Vec::new() } else { jbytes_to_vec(&env, &fields_raw) };
    vec_to_jbytes(&env, &lxmf_rust::display_name::decode_fields_bytes(&bytes).to_trailer())
}

/// `RetichatBridge.nativeRouterSetDeliveryCallback(routerHandle: Long, callback: MessageCallback): Int`
///
/// `MessageCallback` is a Kotlin interface with:
/// ```kotlin
/// fun onMessage(hash: ByteArray, srcHash: ByteArray, destHash: ByteArray,
///               title: String, content: String, timestamp: Double,
///               signatureValid: Boolean, unverifiedReason: Int,
///               fieldsRaw: ByteArray, senderKey: ByteArray?, packed: ByteArray?)
/// ```
/// `unverifiedReason`: 0 validated, 1 source unknown (no key yet), 2
/// signature invalid. DISPLAY_NAMES.md §5.2 accepts a 0xD1 name differently
/// for 1 and 2, so decide on it, never on `signatureValid` alone.
///
/// An accept or a leave of a group carries its sender's own key (RFed-spec
/// Group.md, "Member statuses and keys"): `senderKey` is that key (64 bytes)
/// when it binds to the message's source, and a source the router held no key
/// for is then checked under it, so `unverifiedReason` is 0 or 2 for it, as for
/// any signature (`group_status::receipt`). `packed` is the whole message as
/// received (`destination | source | signature | payload`), for an accept or
/// leave of at most 2144 bytes: what a creator keeps. Both are null for every
/// other message. Nothing is remembered here: Kotlin remembers `senderKey`
/// after the group rule has passed.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterSetDeliveryCallback(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    callback: JObject,
) -> jint {
    // Store JVM + global ref to callback object
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            rns::set_error(format!("Failed to get JavaVM: {}", e));
            return -1;
        }
    };
    let global_ref = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("Failed to create global ref: {}", e));
            return -1;
        }
    };

    *DELIVERY_CB.lock().unwrap() = Some((jvm, global_ref));

    // `lxmf::router_set_delivery_callback` hands over a `ReceivedMessage`
    // snapshot, which holds neither the packed bytes nor the signature. The
    // callback is registered on the router itself to be given the message
    // (`LXMRouter::register_delivery_callback`, which that wrapper calls too).
    let Some(router) = rns::get_handle::<Arc<Mutex<lxmf_rust::LXMRouter>>>(router as u64) else {
        rns::set_error("invalid router handle".to_string());
        return -1;
    };
    let registered = match router.lock() {
        Ok(mut router) => {
            router.register_delivery_callback(Arc::new(deliver_to_kotlin));
            Ok(())
        }
        Err(e) => Err(e.to_string()),
    };
    ok_or_neg(registered)
}

/// A byte array for Kotlin, or null for none.
fn optional_jbytes<'a>(env: &JNIEnv<'a>, data: Option<&[u8]>) -> JObject<'a> {
    match data {
        Some(bytes) => env.byte_array_from_slice(bytes).map(JObject::from).unwrap_or_else(|_| JObject::null()),
        None => JObject::null(),
    }
}

/// The router's delivery callback: a received message to Kotlin's
/// `MessageCallback.onMessage`, from whatever thread the router delivers on.
fn deliver_to_kotlin(message: &lxmf_rust::LXMessage) {
    let mut msg = lxmf::ReceivedMessage::from_lxmessage(message);
    // An accept or leave's carried key, checked here for a source the router
    // holds no key for; the key and the packed message for Kotlin.
    let receipt = group_status::receipt(message, msg.signature_validated, msg.unverified_reason);
    msg.signature_validated = receipt.validated;
    msg.unverified_reason = receipt.unverified_reason;

    let guard = DELIVERY_CB.lock().unwrap();
    let (jvm, cb_ref) = match guard.as_ref() {
        Some(pair) => pair,
        None => return,
    };

    // Attach current thread to JVM
    let mut env = match jvm.attach_current_thread() {
        Ok(env) => env,
        Err(_) => return,
    };

    // Build arguments
    let j_hash = env.byte_array_from_slice(&msg.hash).unwrap();
    let j_src = env.byte_array_from_slice(&msg.source_hash).unwrap();
    let j_dest = env.byte_array_from_slice(&msg.destination_hash).unwrap();
    let j_title = env.new_string(&msg.title).unwrap();
    let j_content = env.new_string(&msg.content).unwrap();

    // Raw LXMF fields (msgpack bytes — Kotlin decodes)
    let j_fields = env.byte_array_from_slice(&msg.fields_raw).unwrap();
    let j_key = optional_jbytes(&env, receipt.sender_key.as_deref());
    let j_packed = optional_jbytes(&env, receipt.packed.as_deref());

    let call_result = env.call_method(
        cb_ref.as_obj(),
        "onMessage",
        "([B[B[BLjava/lang/String;Ljava/lang/String;DZI[B[B[B)V",
        &[
            JValue::Object(&j_hash),
            JValue::Object(&j_src),
            JValue::Object(&j_dest),
            JValue::Object(&JObject::from(j_title)),
            JValue::Object(&JObject::from(j_content)),
            JValue::Double(msg.timestamp),
            JValue::Bool(msg.signature_validated as u8),
            // 0 validated, 1 source unknown, 2 signature invalid
            // (DISPLAY_NAMES.md §5.2 treats 1 and 2 differently).
            JValue::Int(msg.unverified_reason as i32),
            JValue::Object(&j_fields),
            JValue::Object(&j_key),
            JValue::Object(&j_packed),
        ],
    );
    if let Err(e) = call_result {
        android_log(&format!("delivery callback call_method failed: {}", e));
    }
    match env.exception_check() {
        Ok(true) => {
            android_log("delivery callback raised Java exception");
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }
        Ok(false) => {}
        Err(e) => {
            android_log(&format!("delivery callback exception_check failed: {}", e));
        }
    }
}

/// `RetichatBridge.nativeRouterSetMessageStateCallback(router: Long, callback: MessageStateCallback): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterSetMessageStateCallback(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    callback: JObject,
) -> jint {
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => { rns::set_error(format!("Failed to get JavaVM: {e}")); return -1; }
    };
    let global_ref = match env.new_global_ref(&callback) {
        Ok(reference) => reference,
        Err(e) => { rns::set_error(format!("Failed to create state callback ref: {e}")); return -1; }
    };
    *MESSAGE_STATE_CB.lock().unwrap() = Some((jvm, global_ref));

    ok_or_neg(lxmf::router_set_message_state_callback(
        router as u64,
        Arc::new(move |hash, state| {
            let guard = MESSAGE_STATE_CB.lock().unwrap();
            let (jvm, cb_ref) = match guard.as_ref() { Some(pair) => pair, None => return };
            let mut env = match jvm.attach_current_thread() { Ok(env) => env, Err(_) => return };
            let j_hash = match env.byte_array_from_slice(hash) { Ok(value) => value, Err(_) => return };
            let _ = env.call_method(
                cb_ref.as_obj(),
                "onState",
                "([BI)V",
                &[JValue::Object(&j_hash), JValue::Int(state as jint)],
            );
        }),
    ))
}

/// `RetichatBridge.nativeRouterSetAnnounceCallback(routerHandle: Long, callback: AnnounceCallback): Int`
///
/// `AnnounceCallback` is a Kotlin interface with:
/// ```kotlin
/// fun onAnnounce(destHash: ByteArray, displayName: String?)
/// ```
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterSetAnnounceCallback(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    callback: JObject,
) -> jint {
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            rns::set_error(format!("Failed to get JavaVM: {}", e));
            return -1;
        }
    };
    let global_ref = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("Failed to create global ref: {}", e));
            return -1;
        }
    };

    *ANNOUNCE_CB.lock().unwrap() = Some((jvm, global_ref));

    let result = lxmf::router_set_announce_callback(
        router as u64,
        Arc::new(move |dest_hash: &[u8], display_name: Option<String>| {
            let guard = ANNOUNCE_CB.lock().unwrap();
            let (jvm, cb_ref) = match guard.as_ref() {
                Some(pair) => pair,
                None => return,
            };

            let mut env = match jvm.attach_current_thread() {
                Ok(env) => env,
                Err(_) => return,
            };

            let j_hash = match env.byte_array_from_slice(dest_hash) {
                Ok(h) => h,
                Err(_) => return,
            };

            let j_name = match &display_name {
                Some(name) => match env.new_string(name) {
                    Ok(s) => JObject::from(s),
                    Err(_) => JObject::null(),
                },
                None => JObject::null(),
            };

            let _ = env.call_method(
                cb_ref.as_obj(),
                "onAnnounce",
                "([BLjava/lang/String;)V",
                &[
                    JValue::Object(&j_hash),
                    JValue::Object(&j_name),
                ],
            );
        }),
    );

    ok_or_neg(result)
}

/// `RetichatBridge.nativeRouterAnnounce(router: Long, destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterAnnounce(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(lxmf::router_announce(router as u64, &h))
}

/// `RetichatBridge.nativeTransportPublishDestination(destHash: ByteArray, refreshSecs: Double): Int`
///
/// Opt the given destination hash into Transport's auto-announce daemon.
/// Transport will then re-announce automatically:
///   * once on every interface false→true online transition, and
///   * every `refresh_secs` seconds (pass 0.0 for up-edge-only),
/// both held per destination and per interface to one announce per
/// period (`refresh_secs`, or 30 min when 0.0) since the last announce
/// there, including the app's own announces, which always go out.
///
/// For the router's delivery destination the published announces carry the
/// router's app_data (`[announce_name | nil, stamp_cost]`), which the router
/// keeps current when the Announce Display Name changes (DISPLAY_NAMES.md §2.2).
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeTransportPublishDestination(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
    refresh_secs: jni::sys::jdouble,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(lxmf::publish_destination(&h, refresh_secs))
}

/// `RetichatBridge.nativeTransportUnpublishDestination(destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeTransportUnpublishDestination(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    rns::transport_unpublish_destination(&h);
    0
}

/// `RetichatBridge.nativeRouterWatchDestination(router: Long, destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterWatchDestination(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(lxmf::router_watch_destination(router as u64, &h))
}

/// `RetichatBridge.nativeRouterSetFilterStrangers(router: Long, enabled: Boolean): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterSetFilterStrangers(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
    enabled: jni::sys::jboolean,
) -> jint {
    ok_or_neg(lxmf::router_set_delivery_filter_strangers(router as u64, enabled != 0))
}

/// `RetichatBridge.nativeRouterClearDeliveryAllowlist(router: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterClearDeliveryAllowlist(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jint {
    ok_or_neg(lxmf::router_clear_delivery_allowlist(router as u64))
}

/// `RetichatBridge.nativeRouterAllowDeliveryIdentity(router: Long, identityHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterAllowDeliveryIdentity(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    identity_hash: JByteArray,
) -> jint {
    let hash = jbytes_to_vec(&env, &identity_hash);
    ok_or_neg(lxmf::router_allow_delivery_identity(router as u64, &hash))
}

/// `RetichatBridge.nativeRouterDisallowDeliveryIdentity(router: Long, identityHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterDisallowDeliveryIdentity(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    identity_hash: JByteArray,
) -> jint {
    let hash = jbytes_to_vec(&env, &identity_hash);
    ok_or_neg(lxmf::router_disallow_delivery_identity(router as u64, &hash))
}

/// `RetichatBridge.nativeRouterProcessOutbound(router: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterProcessOutbound(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jint {
    ok_or_neg(lxmf::router_process_outbound(router as u64))
}

/// `RetichatBridge.nativeRouterDestroy(router: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterDestroy(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jint {
    ok_or_neg(lxmf::router_destroy(router as u64))
}

// ---- Message ----

/// `RetichatBridge.nativeMessageCreate(destHash: ByteArray, srcHash: ByteArray, content: String, title: String, method: Int, identityHandle: Long): Long`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageCreate(
    mut env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
    src_hash: JByteArray,
    content: JString,
    title: JString,
    method: jint,
    identity_handle: jlong,
) -> jlong {
    let dh = jbytes_to_vec(&env, &dest_hash);
    let sh = jbytes_to_vec(&env, &src_hash);
    let c = jstring_to_string(&mut env, &content);
    let t = jstring_to_string(&mut env, &title);
    ok_or_zero(lxmf::message_create(&dh, &sh, &c, &t, method as u8, identity_handle as u64))
}

/// `RetichatBridge.nativeMessageAddAttachment(handle: Long, filename: String, data: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageAddAttachment(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    filename: JString,
    data: JByteArray,
) -> jint {
    let f = jstring_to_string(&mut env, &filename);
    let d = jbytes_to_vec(&env, &data);
    ok_or_neg(lxmf::message_add_attachment(handle as u64, &f, &d))
}

/// An LXMF field key from Kotlin's Int: `lxmf_rust::ffi::field_key` rejects
/// anything outside 0..=255 (-1, `nativeLastError`) rather than truncating
/// it, which used to let e.g. 0x1D1 alias 0xD1 (FIELD_RETICHAT). The
/// generic setters refuse 0xD1 itself: its entries go through
/// `nativeMessageSetRetichat*`.
fn field_key(key: jint) -> Option<u8> {
    match lxmf::field_key(key as i64) {
        Ok(key) => Some(key),
        Err(e) => {
            rns::set_error(e);
            None
        }
    }
}

/// `RetichatBridge.nativeMessageAddFieldString(handle: Long, key: Int, value: String): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageAddFieldString(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: jint,
    value: JString,
) -> jint {
    let Some(key) = field_key(key) else { return -1 };
    let v = jstring_to_string(&mut env, &value);
    ok_or_neg(lxmf::message_add_field_string(handle as u64, key, &v))
}

/// `RetichatBridge.nativeMessageAddFieldBool(handle: Long, key: Int, value: Boolean): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageAddFieldBool(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: jint,
    value: jni::sys::jboolean,
) -> jint {
    let Some(key) = field_key(key) else { return -1 };
    ok_or_neg(lxmf::message_add_field_bool(handle as u64, key, value != 0))
}

/// `RetichatBridge.nativeMessageSetRetichatString(handle: Long, key: Int, value: String): Int`
///
/// DISPLAY_NAMES.md §10: set an entry of the Retichat field 0xD1, merged
/// into `{0xD1: {key: value}}` with the entries already there. `key` must be
/// 1..127 (0 is the display name, which only the router writes); keys 1-9
/// are the group entries, all strings except 8. Anything else is refused:
/// -1 with `nativeLastError`, and the message is unchanged. A null value is
/// refused too.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageSetRetichatString(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: jint,
    value: JString,
) -> jint {
    if value.is_null() {
        rns::set_error("value is null".to_string());
        return -1;
    }
    let v: String = match env.get_string(&value) {
        Ok(js) => js.into(),
        Err(e) => {
            rns::set_error(format!("value: {e}"));
            return -1;
        }
    };
    ok_or_neg(lxmf::message_set_retichat_string(handle as u64, key as i64, &v))
}

/// `RetichatBridge.nativeMessageSetRetichatBool(handle: Long, key: Int, value: Boolean): Int`
///
/// As `nativeMessageSetRetichatString`, for a bool entry; of the defined
/// keys only 8 (group relay done) is a bool.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageSetRetichatBool(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: jint,
    value: jni::sys::jboolean,
) -> jint {
    ok_or_neg(lxmf::message_set_retichat_bool(handle as u64, key as i64, value != 0))
}

/// `RetichatBridge.nativeMessageSetRetichatStatuses(handle: Long, elements: Array<ByteArray>): Int`
///
/// RFed-spec Group.md, "Member statuses and keys" and DISPLAY_NAMES.md §10 key
/// 10: write `GROUP_STATUSES` on the outbound message `handle`: a native array
/// of bin, one for each of `elements` (a member's own packed accept or leave
/// as the creator received it), always in the Retichat field 0xD1 (key 10 has
/// no old top-level field), beside the entries already there. An empty array
/// is a creator's valid answer. Each element is as it was received, never
/// wrapped again (CHECK_THESE_THINGS_FIRST §11). 0 on success; -1 with
/// `nativeLastError` and the message unchanged for an element no receiver
/// would read (no payload, or over 2144 bytes) or a null array or element.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageSetRetichatStatuses(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    elements: JObjectArray,
) -> jint {
    if elements.is_null() {
        rns::set_error("elements is null".to_string());
        return -1;
    }
    let count = match env.get_array_length(&elements) {
        Ok(count) => count,
        Err(e) => {
            rns::set_error(format!("elements: {e}"));
            return -1;
        }
    };
    let mut list = Vec::with_capacity(count.max(0) as usize);
    for index in 0..count {
        let element = match env.get_object_array_element(&elements, index) {
            Ok(element) if !element.is_null() => element,
            _ => {
                rns::set_error(format!("element {index} is null"));
                return -1;
            }
        };
        list.push(jbytes_to_vec(&env, &JByteArray::from(element)));
    }
    let Some(msg) = rns::get_handle::<Arc<Mutex<lxmf_rust::lx_message::LXMessage>>>(handle as u64) else {
        rns::set_error("invalid message handle".to_string());
        return -1;
    };
    let result = match msg.lock() {
        Ok(mut m) => group_status::set_statuses(&mut m, &list),
        Err(e) => Err(e.to_string()),
    };
    ok_or_neg(result)
}

/// `RetichatBridge.nativeGroupUnpackPacked(packed: ByteArray): ByteArray?`
///
/// An element of a `status` (a member's own packed accept or leave, as the
/// creator received it) or an entry held for its sender's key, unpacked and
/// checked as if it had arrived directly: the router's own unpack and
/// signature check under the key held for the source, then the key the
/// message carries for its own sender when none is held
/// (`group_status::unpack_element`). Nothing is remembered. Returns the
/// buffer `group_status::encode_element` documents (verdict, bound key flag,
/// destination, source, message hash, timestamp, the key, the fields), or
/// null with `nativeLastError` when `packed` is not an LXMF message of the
/// size of an accept or leave (97..=2144 bytes). The bytes are another
/// member's: a panic is caught and reported as null.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeGroupUnpackPacked(
    env: JNIEnv,
    _class: JClass,
    packed: JByteArray,
) -> jbyteArray {
    let packed = jbytes_to_vec(&env, &packed);
    let result = std::panic::catch_unwind(|| {
        group_status::unpack_element(&packed).map(|element| group_status::encode_element(&element))
    })
    .unwrap_or_else(|_| Err("unpacking the message panicked".to_string()));
    bytes_or_null(&env, result)
}

/// `RetichatBridge.nativeRouterIngestPropagated(routerHandle: Long, lxmfData: ByteArray): Boolean`
///
/// Hand the router an LXMF message RFed pushed on `rfed.propagation.stream`:
/// the bare propagation blob, `dest(16) | encrypted`, as a propagation sync
/// would have fetched it. True when the router took it for one of its
/// delivery destinations.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterIngestPropagated(
    env: JNIEnv,
    _class: JClass,
    router_handle: jlong,
    lxmf_data: JByteArray,
) -> jni::sys::jboolean {
    let data = jbytes_to_vec(&env, &lxmf_data);
    match lxmf::router_ingest_propagated_lxmf(router_handle as u64, &data) {
        Ok(true) => jni::sys::JNI_TRUE,
        Ok(false) => jni::sys::JNI_FALSE,
        Err(e) => {
            rns::set_error(e);
            jni::sys::JNI_FALSE
        }
    }
}

/// `RetichatBridge.nativeMessageClonePropagated(handle: Long): Long`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageClonePropagated(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    match lxmf::message_clone_propagated(handle as u64) {
        Ok(copy) => copy as jlong,
        Err(e) => {
            rns::set_error(e);
            0
        }
    }
}

/// `RetichatBridge.nativeMessageSend(router: Long, msg: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageSend(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
    msg: jlong,
) -> jint {
    ok_or_neg(lxmf::message_send(router as u64, msg as u64))
}

/// `RetichatBridge.nativeMessageSendViaAppLinks(msg: Long): Int`
///
/// Routes the outbound message via Reticulum's `AppLinks::send` pipeline:
/// parallel iface-race `request_path` (with 2 s liveness cache, LoRa
/// skip), then dispatch on the global `LXMRouter`. No router handle is
/// required. Returns 0 on success, -1 on failure (call `nativeLastError`).
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageSendViaAppLinks(
    _env: JNIEnv,
    _class: JClass,
    msg: jlong,
) -> jint {
    ok_or_neg(lxmf::message_send_via_app_links(msg as u64))
}

/// `RetichatBridge.nativePeerIsDistro(destHash: ByteArray): Boolean`
///
/// RFed SPEC §17.10: true when the destination's last announce carried the
/// distro flag; the app then propagates at once instead of trying a direct
/// link that nothing answers.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePeerIsDistro(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jni::sys::jboolean {
    let bytes = jbytes_to_vec(&env, &dest_hash);
    if lxmf::peer_is_distro(&bytes) { 1 } else { 0 }
}

/// `RetichatBridge.nativeAppLinksInvalidateLiveness(destHash: ByteArray): Int`
///
/// Forget the cached liveness winner for `destHash`. Call from your
/// `ConnectivityManager.NetworkCallback` when the active network flips
/// (WiFi lost, cellular came up) so the next AppLinks send re-races.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeAppLinksInvalidateLiveness(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jint {
    let bytes = jbytes_to_vec(&env, &dest_hash);
    if bytes.is_empty() {
        return -1;
    }
    lxmf::app_links_invalidate_liveness(&bytes);
    0
}

/// `RetichatBridge.nativeMessageGetState(handle: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageGetState(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    match lxmf::message_get_state(handle as u64) {
        Ok(s) => s as jint,
        Err(e) => {
            rns::set_error(e);
            -1
        }
    }
}

/// `RetichatBridge.nativeMessageGetProgress(handle: Long): Float`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageGetProgress(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jfloat {
    match lxmf::message_get_progress(handle as u64) {
        Ok(p) => p,
        Err(e) => {
            rns::set_error(e);
            -1.0
        }
    }
}

/// `RetichatBridge.nativeMessageGetHash(handle: Long): ByteArray?`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageGetHash(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    match lxmf::message_get_hash(handle as u64) {
        Ok(h) => vec_to_jbytes(&env, &h),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

/// `RetichatBridge.nativeMessageDestroy(handle: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    ok_or_neg(lxmf::message_destroy(handle as u64))
}

// ---- Propagation ----

/// `RetichatBridge.nativeRouterSetPropagationNode(router: Long, destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterSetPropagationNode(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(lxmf::router_set_propagation_node(router as u64, &h))
}

/// `RetichatBridge.nativeRouterRequestMessages(router: Long, identity: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterRequestMessages(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
    identity: jlong,
) -> jint {
    ok_or_neg(lxmf::router_request_messages(router as u64, identity as u64))
}

/// `RetichatBridge.nativeRouterGetPropagationState(router: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterGetPropagationState(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jint {
    match lxmf::router_get_propagation_state(router as u64) {
        Ok(s) => s as jint,
        Err(e) => {
            rns::set_error(e);
            -1
        }
    }
}

/// `RetichatBridge.nativeRouterGetPropagationProgress(router: Long): Float`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterGetPropagationProgress(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jfloat {
    match lxmf::router_get_propagation_progress(router as u64) {
        Ok(p) => p as jfloat,
        Err(e) => {
            rns::set_error(e);
            -1.0
        }
    }
}

/// `RetichatBridge.nativeRouterCancelPropagation(router: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterCancelPropagation(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jint {
    ok_or_neg(lxmf::router_cancel_propagation(router as u64))
}

// ---------------------------------------------------------------------------
// Announce filtering
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeSetDropAnnounces(enabled: Boolean): Unit`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeSetDropAnnounces(
    _env: JNIEnv,
    _class: JClass,
    enabled: jni::sys::jboolean,
) {
    rns::set_drop_announces(enabled != 0);
}

// ---------------------------------------------------------------------------
// Keepalive tuning
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeSetKeepaliveInterval(secs: Double): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeSetKeepaliveInterval(
    _env: JNIEnv,
    _class: JClass,
    secs: jni::sys::jdouble,
) -> jint {
    ok_or_neg(rns::set_keepalive_interval(secs))
}

// ---------------------------------------------------------------------------
// Identity sign (Ed25519)
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeIdentitySign(handle: Long, data: ByteArray): ByteArray?`
/// Returns 64-byte Ed25519 signature, or null on error.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeIdentitySign(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
) -> jbyteArray {
    let d = jbytes_to_vec(&env, &data);
    match rns::identity_sign(handle as u64, &d) {
        Ok(sig) => vec_to_jbytes(&env, &sig),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// Announce watchlist
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeWatchAnnounce(destHash: ByteArray): Unit`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeWatchAnnounce(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) {
    let h = jbytes_to_vec(&env, &dest_hash);
    rns::watch_announce(h);
}

/// `RetichatBridge.nativeUnwatchAnnounce(destHash: ByteArray): Unit`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeUnwatchAnnounce(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) {
    let h = jbytes_to_vec(&env, &dest_hash);
    rns::unwatch_announce(&h);
}

// ---------------------------------------------------------------------------
// Transport: persist path table
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeTransportSavePaths(): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeTransportSavePaths(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    Transport::save_path_table();
    0
}

// ---------------------------------------------------------------------------
// Raw packet send to hash (used by FCM token registration & channel SEND)
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativePacketSendToHash(destHash, app, aspects, payload): Int`
/// `aspects` may be dot- or comma-separated.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePacketSendToHash(
    mut env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
    app_name: JString,
    aspects: JString,
    payload: JByteArray,
) -> jint {
    let hash = jbytes_to_vec(&env, &dest_hash);
    let app = jstring_to_string(&mut env, &app_name);
    let asp_str = jstring_to_string(&mut env, &aspects);
    let asp_vec = parse_destination_aspects(&app, &asp_str);
    let data = jbytes_to_vec(&env, &payload);

    let dest_handle = match rns::destination_create_outbound_from_hash(&hash, &app, asp_vec) {
        Ok(h) => h,
        Err(e) => {
            rns::set_error(e);
            return -1;
        }
    };
    let pkt_handle = match rns::packet_create(dest_handle, &data, false) {
        Ok(h) => h,
        Err(e) => {
            rns::destroy_handle(dest_handle);
            rns::set_error(e);
            return -1;
        }
    };
    rns::destroy_handle(dest_handle);
    match rns::packet_send(pkt_handle) {
        Ok(_) => 0,
        Err(e) => {
            rns::set_error(e);
            -1
        }
    }
}

// ---------------------------------------------------------------------------
// Synchronous link request (blocks — must be called from a background thread)
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeLinkRequest(destHash, app, aspects, identity, path, payload, timeoutSecs): ByteArray?`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeLinkRequest(
    mut env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
    app_name: JString,
    aspects: JString,
    identity_handle: jlong,
    path: JString,
    payload: JByteArray,
    timeout_secs: jni::sys::jdouble,
) -> jbyteArray {
    let hash = jbytes_to_vec(&env, &dest_hash);
    let app = jstring_to_string(&mut env, &app_name);
    let asp_str = jstring_to_string(&mut env, &aspects);
    let asp_vec = parse_destination_aspects(&app, &asp_str);
    let p = jstring_to_string(&mut env, &path);
    let data = jbytes_to_vec(&env, &payload);

    match rns::link_request(&hash, &app, asp_vec, identity_handle as u64, &p, &data, timeout_secs) {
        Ok(response) => vec_to_jbytes(&env, &response),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// RFed Delivery — inbound channel blob endpoint (JNI callback)
// ---------------------------------------------------------------------------
//
// Single global delivery state.  Callback is delivered to a Kotlin object
// implementing:
//   interface RfedBlobCallback { fun onBlob(blob: ByteArray) }

struct RfedDeliveryState {
    dest: Destination,
    _callback: GlobalRef,
}

static RFED_DELIVERY: Mutex<Option<RfedDeliveryState>> = Mutex::new(None);
static RFED_DELIVERY_CB: Mutex<Option<(JavaVM, GlobalRef)>> = Mutex::new(None);

/// `RetichatBridge.nativeRfedDeliveryStart(identityHandle: Long, callback: RfedBlobCallback): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRfedDeliveryStart(
    mut env: JNIEnv,
    _class: JClass,
    identity_handle: jlong,
    callback: JObject,
) -> jint {
    let identity: Identity = match rns::get_handle(identity_handle as u64) {
        Some(id) => id,
        None => {
            rns::set_error("invalid identity handle".into());
            return -1;
        }
    };

    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            rns::set_error(format!("get_java_vm: {}", e));
            return -1;
        }
    };
    let global_ref = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("new_global_ref: {}", e));
            return -1;
        }
    };
    let cb_for_storage = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("new_global_ref(2): {}", e));
            return -1;
        }
    };

    *RFED_DELIVERY_CB.lock().unwrap() = Some((jvm, global_ref));

    let mut dest = match Destination::new_inbound(
        Some(identity),
        DestinationType::Single,
        "rfed".to_string(),
        vec!["delivery".to_string()],
    ) {
        Ok(d) => d,
        Err(e) => {
            rns::set_error(e);
            return -1;
        }
    };
    // Prove every packet RFed delivers here, so RFed can count a delivery
    // only when it is proved and queue and push the rest (RFed SPEC §7).
    // Until 2026-09-26 nothing was proved: every rfed.delivery packet was
    // one RFed could not confirm.
    if let Err(e) = dest.set_proof_strategy(reticulum_rust::destination::PROVE_ALL) {
        rns::set_error(e);
        return -1;
    }

    let packet_cb: Arc<dyn Fn(&[u8], &Packet) + Send + Sync> =
        Arc::new(move |data: &[u8], _pkt: &Packet| {
            let guard = RFED_DELIVERY_CB.lock().unwrap();
            let (jvm, cb_ref) = match guard.as_ref() {
                Some(p) => p,
                None => return,
            };
            let mut env = match jvm.attach_current_thread() {
                Ok(e) => e,
                Err(_) => return,
            };
            let j_blob = match env.byte_array_from_slice(data) {
                Ok(b) => b,
                Err(_) => return,
            };
            let _ = env.call_method(
                cb_ref.as_obj(),
                "onBlob",
                "([B)V",
                &[JValue::Object(&j_blob)],
            );
        });
    dest.set_packet_callback(Some(packet_cb));
    Transport::register_destination(dest.clone());

    // Opt rfed.delivery into the auto-announce daemon so the RFed node
    // always has a fresh path back to this device:
    //   * re-announced on every interface false→true transition, and
    //   * every 30 minutes for as long as the stack is running,
    // both held per interface to one announce per 30 minutes.
    // This replaces the old manual one-shot rfedDeliveryAnnounce() calls
    // from Kotlin — the daemon is strictly superior because it fires on
    // interface up-edges that happen while the app is backgrounded.
    Transport::publish_destination(
        dest.hash.clone(),
        Some(std::time::Duration::from_secs(30 * 60)),
        None,
    );

    *RFED_DELIVERY.lock().unwrap() = Some(RfedDeliveryState { dest, _callback: cb_for_storage });
    0
}

/// `RetichatBridge.nativeRfedDeliveryAnnounce(): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRfedDeliveryAnnounce(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let mut guard = RFED_DELIVERY.lock().unwrap();
    if let Some(ref mut state) = *guard {
        if let Err(e) = state.dest.announce(None, false, None, None, true) {
            rns::set_error(e);
            return -1;
        }
        return 0;
    }
    rns::set_error("rfed delivery not started".into());
    -1
}

/// `RetichatBridge.nativeRfedDeliveryStop(): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRfedDeliveryStop(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let mut guard = RFED_DELIVERY.lock().unwrap();
    if let Some(state) = guard.take() {
        Transport::unpublish_destination(&state.dest.hash);
        Transport::deregister_destination(&state.dest.hash);
    }
    *RFED_DELIVERY_CB.lock().unwrap() = None;
    0
}

// ---------------------------------------------------------------------------
// Channel crypto / stamp / LXM pack-unpack
//
// The channel key derivation and LXM pack/unpack live once in
// `lxmf_rust::channel`, shared with Retichat-ios/rust/retichat-ffi, so the
// wire format and the key-binding check (DISPLAY_NAMES.md §2.3) cannot
// drift between the platforms. See
// /memories/repo/retichat-rfed-channel-integration.md for the wire-format
// contract and historical regressions.
// ---------------------------------------------------------------------------

fn channel_identity(name: &str) -> Result<Identity, String> {
    lxmf_rust::channel::channel_identity(name)
}

fn channel_destination(name: &str) -> Result<Destination, String> {
    lxmf_rust::channel::channel_destination(name)
}

/// `RetichatBridge.nativeChannelEncrypt(name: String, plaintext: ByteArray): ByteArray?`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeChannelEncrypt(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    plaintext: JByteArray,
) -> jbyteArray {
    let n = jstring_to_string(&mut env, &name);
    let pt = jbytes_to_vec(&env, &plaintext);
    let identity = match channel_identity(&n) {
        Ok(id) => id,
        Err(e) => {
            rns::set_error(e);
            return std::ptr::null_mut();
        }
    };
    match identity.encrypt(&pt) {
        Ok(ct) => vec_to_jbytes(&env, &ct),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

/// `RetichatBridge.nativeChannelDecrypt(name: String, ciphertext: ByteArray): ByteArray?`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeChannelDecrypt(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    ciphertext: JByteArray,
) -> jbyteArray {
    let n = jstring_to_string(&mut env, &name);
    let ct = jbytes_to_vec(&env, &ciphertext);
    let mut identity = match channel_identity(&n) {
        Ok(id) => id,
        Err(e) => {
            rns::set_error(e);
            return std::ptr::null_mut();
        }
    };
    match identity.decrypt(&ct) {
        Ok(pt) => vec_to_jbytes(&env, &pt),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

/// `RetichatBridge.nativeComputeChannelStamp(payload: ByteArray, cost: Int): ByteArray?`
///
/// Returns 32-byte stamp, or null when cost == 0 (no stamp required) or PoW
/// fails.  See iOS retichat_compute_channel_stamp for the contract.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeComputeChannelStamp(
    env: JNIEnv,
    _class: JClass,
    payload: JByteArray,
    cost: jint,
) -> jbyteArray {
    if cost <= 0 {
        return std::ptr::null_mut();
    }
    let cost_u = cost as u32;
    let data = jbytes_to_vec(&env, &payload);
    let transient_id = reticulum_rust::identity::full_hash(&data);
    let workblock = LXStamper::stamp_workblock(&transient_id, 16);
    let (stamp, value) = LXStamper::generate_stamp(&transient_id, cost_u, 16);
    // None when the search is cancelled or times out; an empty stamp fails the
    // length check in stamp_valid and takes the error path below.
    let stamp = stamp.unwrap_or_default();
    if value < cost_u || !LXStamper::stamp_valid(&stamp, cost_u, &workblock) {
        rns::set_error(format!(
            "stamp PoW failed: required cost={} but achieved value={} (payload_len={})",
            cost_u, value, data.len()
        ));
        return std::ptr::null_mut();
    }
    vec_to_jbytes(&env, &stamp)
}

/// `RetichatBridge.nativeChannelLxmPack(name, senderHandle, content, title, displayNameState, displayName): ByteArray?`
///
/// Pack and unpack live once in `lxmf_rust::channel`, shared with the iOS
/// FFI (`retichat_channel_lxm_pack`); this is a thin wrapper. Returns the
/// same 8-byte-timestamp-prefixed buffer iOS produces:
///   [ ts_ms_be(8) | channel_id_hash(16) | EC_encrypted(prelude || lxmf_tail) ]
/// Caller strips the first 8 bytes before sending; uses tsMs for local dedup.
///
/// `displayNameState` is the Channel Display Name to carry in key 0 of field
/// 0xD1 (DISPLAY_NAMES.md §2.3, §4.2): 0 = none (no 0xD1; the bytes are
/// exactly the pre-name format), 1 = clear ({0xD1: {0: empty bin}}), 2 = the name in
/// `displayName` (raw UTF-8, cleaned here; a name that cleans to nothing is
/// an error). `displayName` may be null for states 0 and 1.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeChannelLxmPack(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    sender_handle: jlong,
    content: JByteArray,
    title: JByteArray,
    display_name_state: jint,
    display_name: JByteArray,
) -> jbyteArray {
    let n = jstring_to_string(&mut env, &name);
    let content_v = jbytes_to_vec(&env, &content);
    let title_v = jbytes_to_vec(&env, &title);
    let name_raw = if display_name.is_null() { Vec::new() } else { jbytes_to_vec(&env, &display_name) };
    let state = match u8::try_from(display_name_state) {
        Ok(state) => state,
        Err(_) => {
            rns::set_error(format!("unknown display name state {display_name_state} (0 none, 1 clear, 2 name)"));
            return std::ptr::null_mut();
        }
    };
    let post_name = match lxmf_rust::channel::post_name_from_state(state, &name_raw) {
        Ok(post_name) => post_name,
        Err(e) => {
            rns::set_error(e);
            return std::ptr::null_mut();
        }
    };
    let Some(sender) = rns::get_handle::<Identity>(sender_handle as u64) else {
        rns::set_error("invalid sender identity handle".into());
        return std::ptr::null_mut();
    };
    bytes_or_null(
        &env,
        lxmf_rust::channel::pack(&n, &sender, &content_v, &title_v, &post_name).map(|post| post.to_bridge_bytes()),
    )
}

/// `RetichatBridge.nativeChannelLxmUnpack(name: String, lxmfData: ByteArray): ByteArray?`
///
/// A post whose embedded public key does not produce its claimed source
/// hash is rejected (null; `nativeLastError` mentions "key binding") and no
/// key is remembered (DISPLAY_NAMES.md §2.3).
///
/// Returns the same flat parsed-message buffer iOS produces:
///   [0..16]   source_hash
///   [16..24]  timestamp_ms_be
///   [24]      signature_validated (0/1)
///   [25]      unverified_reason (0=ok, 1=SOURCE_UNKNOWN, 2=SIGNATURE_INVALID)
///   [26..28]  title_len_be (u16)
///   [28..32]  content_len_be (u32)
///   [32..]    title bytes, then content bytes
///   then      name_state u8 (0=absent, 1=clear, 2=name — the post's Channel
///             Display Name, reported only when the signature validated),
///             name_len u16 BE, name bytes (cleaned UTF-8)
/// The name trailer sits at the end, so decoders that read only the first
/// 32+t+c bytes keep working.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeChannelLxmUnpack(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    lxmf_data: JByteArray,
) -> jbyteArray {
    let n = jstring_to_string(&mut env, &name);
    let data = jbytes_to_vec(&env, &lxmf_data);
    bytes_or_null(&env, lxmf_rust::channel::unpack(&n, &data).and_then(|post| post.to_bridge_bytes()))
}

/// `RetichatBridge.nativeChannelHash16(name: String): ByteArray?`
///
/// Returns the 16-byte `lxmf.delivery` DESTINATION hash of the channel
/// identity derived from `name`: the hash the inner LXMF message is
/// addressed to and signed over. It is NOT the channel hash. The channel
/// hash (the wire prefix of every post, and RFed's key for subscribe, pull,
/// fan-out and notify) is the channel IDENTITY hash,
/// `lxmf_rust::channel::channel_id_hash` (RFed-rust SPEC.md §1).
///
/// Nothing calls this: `RetichatBridge.kt` declares no `external fun
/// nativeChannelHash16`. The app's channel hash is
/// `RetichatBridge.channelHash16`, computed in Kotlin by
/// `crypto/ChannelHash.compute` (the identity hash), and posts get their
/// prefix from `lxmf_rust::channel::pack`. Do not wire this in as the
/// channel hash.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeChannelHash16(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jbyteArray {
    let n = jstring_to_string(&mut env, &name);
    let dest = match channel_destination(&n) {
        Ok(d) => d,
        Err(e) => {
            rns::set_error(e);
            return std::ptr::null_mut();
        }
    };
    let hash = dest.hash.to_vec();
    vec_to_jbytes(&env, &hash)
}

// ---------------------------------------------------------------------------
// APP_LINK — persistent push-driven links
//
// Direct port of the iOS APP_LINK FFI surface (see
// Retichat-ios/Frameworks/RetichatFFI.xcframework/.../CRetichatFFI.h
// `lxmf_app_link_*` and Retichat-ios/Retichat/Services/LxmfClient.swift
// AppLink section).  All retries / readiness-waits are owned by the
// caller via the status callback — no polling, no app-level retries
// (DESIGN_PRINCIPLES.md §2, §3).
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeAppLinkOpen(router, destHash, app, aspectsCsv): Int`
///
/// `aspectsCsv` is `.`-separated (e.g. "delivery", "channel"); pass an
/// empty string for no aspects.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeAppLinkOpen(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
    app_name: JString,
    aspects_csv: JString,
) -> jint {
    let hash = jbytes_to_vec(&env, &dest_hash);
    let app = jstring_to_string(&mut env, &app_name);
    let asp_str = jstring_to_string(&mut env, &aspects_csv);
    let asp_owned: Vec<String> = if asp_str.is_empty() {
        Vec::new()
    } else {
        asp_str.split('.').map(|s| s.to_string()).collect()
    };
    let asp_refs: Vec<&str> = asp_owned.iter().map(|s| s.as_str()).collect();
    ok_or_neg(lxmf::router_app_link_open(router as u64, &hash, &app, &asp_refs))
}

/// `RetichatBridge.nativeAppLinkOpenPersistent(router, destHash, app, aspectsCsv): Int`
///
/// Same destination registration as `nativeAppLinkOpen`, but once the
/// path-race succeeds AppLinks holds the outbound link open so request-style
/// traffic can reuse it directly.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeAppLinkOpenPersistent(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
    app_name: JString,
    aspects_csv: JString,
) -> jint {
    let hash = jbytes_to_vec(&env, &dest_hash);
    let app = jstring_to_string(&mut env, &app_name);
    let asp_str = jstring_to_string(&mut env, &aspects_csv);
    let asp_owned: Vec<String> = if asp_str.is_empty() {
        Vec::new()
    } else {
        asp_str.split('.').map(|s| s.to_string()).collect()
    };
    let asp_refs: Vec<&str> = asp_owned.iter().map(|s| s.as_str()).collect();
    ok_or_neg(lxmf::router_app_link_open_persistent(router as u64, &hash, &app, &asp_refs))
}

/// `RetichatBridge.nativeAppLinkClose(router, destHash): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeAppLinkClose(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
) -> jint {
    let hash = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(lxmf::router_app_link_close(router as u64, &hash))
}

/// `RetichatBridge.nativeAppLinkStatus(router, destHash): Int`
///
/// Returns 0..4 (NONE, PATH_REQUESTED, ESTABLISHING, ACTIVE, DISCONNECTED)
/// or -1 on parameter error.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeAppLinkStatus(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
) -> jint {
    let hash = jbytes_to_vec(&env, &dest_hash);
    match lxmf::router_app_link_status(router as u64, &hash) {
        Ok(s) => s as jint,
        Err(e) => {
            rns::set_error(e);
            -1
        }
    }
}

/// `RetichatBridge.nativeAppLinkReopen(router, destHash): Int`
///
/// Explicit deterministic re-open trigger for an existing app link.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeAppLinkReopen(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
) -> jint {
    let hash = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(lxmf::router_app_link_reopen(router as u64, &hash))
}

/// `RetichatBridge.nativeAppLinkRegisterReconnect(router, aspect): Int`
///
/// LXMF only auto-reconnects app-links that announce under `lxmf.delivery`.
/// Call once per extra aspect (e.g. "rfed.channel.subscribe",
/// "rfed.channel.publish", "rfed.notify.register", "rfed.delivery", ...) at
/// startup.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeAppLinkRegisterReconnect(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    aspect: JString,
) -> jint {
    let asp = jstring_to_string(&mut env, &aspect);
    ok_or_neg(lxmf::router_register_app_link_reconnect_handler(
        router as u64,
        &asp,
    ))
}

/// `RetichatBridge.nativeNudgeReconnect()`
///
/// Wake every TCP interface's reconnect loop now (or cut its next wait
/// short if it is mid-attempt) instead of letting it sleep out its backoff,
/// up to 5 min. Call when the network is usable again: a new network, or
/// the OS lifting its block on this app's network. iOS: `rns_nudge_reconnect`.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeNudgeReconnect(
    _env: JNIEnv,
    _class: JClass,
) {
    reticulum_rust::interfaces::tcp_interface::nudge_reconnect();
}

/// `RetichatBridge.nativeAppLinkNetworkChanged(router): Int`
///
/// Triggers ONE fresh attempt for every registered app-link not
/// currently ACTIVE/ESTABLISHING.  Wire this to NetworkMonitor's
/// onAvailable callback — it is the only thing that retries an offline
/// destination (DESIGN_PRINCIPLES.md §1, §3).
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeAppLinkNetworkChanged(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jint {
    ok_or_neg(lxmf::router_app_link_network_changed(router as u64))
}

/// `RetichatBridge.nativeAppLinkRegisterStatusCallback(router, cb): Int`
///
/// `cb` is a Kotlin `AppLinkStatusCallback`:
/// ```kotlin
/// interface AppLinkStatusCallback { fun onStatus(destHash: ByteArray, status: Int) }
/// ```
/// `status` is an `APP_LINK_*` code of app-links: 0 none .. 4 disconnected, and
/// the event 5 recovered (a STALE link heard from its peer again; a handler that
/// acts on ACTIVE alone ignores it).
/// Replaces any previously registered callback (last register wins on
/// the Kotlin side; the underlying Rust registry can hold multiple, but
/// we only need one process-wide fan-out).
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeAppLinkRegisterStatusCallback(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    callback: JObject,
) -> jint {
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            rns::set_error(format!("Failed to get JavaVM: {}", e));
            return -1;
        }
    };
    let global_ref = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("Failed to create global ref: {}", e));
            return -1;
        }
    };
    *APP_LINK_STATUS_CB.lock().unwrap() = Some((jvm, global_ref));

    let result = lxmf::router_register_app_link_status_callback(
        router as u64,
        Arc::new(
            |dest_hash: &[u8], status: u8, _link: Option<reticulum_rust::link::LinkHandle>| {
                let guard = APP_LINK_STATUS_CB.lock().unwrap();
                let (jvm, cb_ref) = match guard.as_ref() {
                    Some(pair) => pair,
                    None => return,
                };
                let mut env = match jvm.attach_current_thread() {
                    Ok(env) => env,
                    Err(_) => return,
                };
                let j_hash = match env.byte_array_from_slice(dest_hash) {
                    Ok(h) => h,
                    Err(_) => return,
                };
                let _ = env.call_method(
                    cb_ref.as_obj(),
                    "onStatus",
                    "([BI)V",
                    &[JValue::Object(&j_hash), JValue::Int(status as jint)],
                );
            },
        ),
    );
    ok_or_neg(result)
}

/// `RetichatBridge.nativeAppLinkRegisterPacketCallback(router, destHash, cb): Int`
///
/// `cb` is a Kotlin `AppLinkPacketCallback`:
/// ```kotlin
/// interface AppLinkPacketCallback { fun onPacket(bytes: ByteArray) }
/// ```
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeAppLinkRegisterPacketCallback(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
    callback: JObject,
) -> jint {
    let hash = jbytes_to_vec(&env, &dest_hash);
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            rns::set_error(format!("Failed to get JavaVM: {}", e));
            return -1;
        }
    };
    let global_ref = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("Failed to create global ref: {}", e));
            return -1;
        }
    };

    let result = lxmf::router_register_app_link_packet_callback(
        router as u64,
        &hash,
        Arc::new(move |data: &[u8]| {
            let mut env = match jvm.attach_current_thread() {
                Ok(env) => env,
                Err(_) => return,
            };
            let j_blob = match env.byte_array_from_slice(data) {
                Ok(blob) => blob,
                Err(_) => return,
            };
            let _ = env.call_method(
                global_ref.as_obj(),
                "onPacket",
                "([B)V",
                &[JValue::Object(&j_blob)],
            );
        }),
    );
    ok_or_neg(result)
}

/// `RetichatBridge.nativeAppLinkSendAsync(router, destHash, appName,
///   aspectsCsv, payload, callback): Int`
///
/// Sends a plain DATA packet via an ephemeral APP_LINK and fires
/// `callback.onResult(status)` exactly once: 0 = delivered (LRPROOF),
/// 1 = failed.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeAppLinkSendAsync(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
    app_name: JString,
    aspects_csv: JString,
    payload: JByteArray,
    callback: JObject,
) -> jint {
    use std::sync::atomic::{AtomicBool, Ordering};

    let hash = jbytes_to_vec(&env, &dest_hash);
    let app = jstring_to_string(&mut env, &app_name);
    let aspects_str = jstring_to_string(&mut env, &aspects_csv);
    let aspects_owned: Vec<String> = if aspects_str.is_empty() {
        Vec::new()
    } else {
        aspects_str.split('.').map(|s| s.to_string()).collect()
    };
    let aspects: Vec<&str> = aspects_owned.iter().map(|s| s.as_str()).collect();
    let data = jbytes_to_vec(&env, &payload);

    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            rns::set_error(format!("Failed to get JavaVM: {}", e));
            return -1;
        }
    };
    let global_ref = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("Failed to create global ref: {}", e));
            return -1;
        }
    };

    let cb_state: Arc<Mutex<Option<(JavaVM, GlobalRef)>>> =
        Arc::new(Mutex::new(Some((jvm, global_ref))));
    let fired = Arc::new(AtomicBool::new(false));

    fn fire(state: &Arc<Mutex<Option<(JavaVM, GlobalRef)>>>, status: i32) {
        let taken = state.lock().unwrap().take();
        let (jvm, cb_ref) = match taken {
            Some(pair) => pair,
            None => return,
        };
        let mut env = match jvm.attach_current_thread() {
            Ok(env) => env,
            Err(_) => return,
        };
        let _ = env.call_method(
            cb_ref.as_obj(),
            "onResult",
            "(I)V",
            &[JValue::Int(status)],
        );
    }

    let state_ok = cb_state.clone();
    let fired_ok = fired.clone();
    let on_delivered: Arc<dyn Fn() + Send + Sync + 'static> = Arc::new(move || {
        if !fired_ok.swap(true, Ordering::SeqCst) {
            fire(&state_ok, 0);
        }
    });

    let state_fail = cb_state.clone();
    let fired_fail = fired;
    let on_failed: Arc<dyn Fn() + Send + Sync + 'static> = Arc::new(move || {
        if !fired_fail.swap(true, Ordering::SeqCst) {
            fire(&state_fail, 1);
        }
    });

    ok_or_neg(lxmf::router_app_link_send(
        router as u64,
        &hash,
        &app,
        &aspects,
        data,
        on_delivered,
        on_failed,
    ))
}

/// `RetichatBridge.nativeAppLinkRequestAsync(router, destHash, path,
///   payload, timeoutSecs, callback): Int`
///
/// Non-blocking variant.  Issues a request on the existing app-link
/// (which should normally be established with `nativeAppLinkOpenPersistent`
/// and have reached ACTIVE). Fires `callback.onResult(status, bytes)` exactly
/// once when the response arrives, the request fails, or the timeout
/// elapses.
///
/// `status`: 0 = response (bytes set), 1 = timeout, 2 = failed,
///           3 = error (callback will NOT fire — check lastError).
///
/// Mirrors `lxmf_app_link_request_async` in LXMF-rust/src/cffi.rs and
/// the iOS Swift trampoline in LxmfClient.swift.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeAppLinkRequestAsync(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
    path: JString,
    payload: JByteArray,
    timeout_secs: jni::sys::jdouble,
    callback: JObject,
) -> jint {
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::Duration;

    let hash = jbytes_to_vec(&env, &dest_hash);
    let path_str = jstring_to_string(&mut env, &path);
    let data = jbytes_to_vec(&env, &payload);

    // Snapshot the link handle for this destination (must already be ACTIVE).
    let link_handle = match lxmf::router_app_link_get_handle(router as u64, &hash) {
        Ok(Some(h)) => h,
        Ok(None) => {
            rns::set_error(
                "no app-link handle to destination — use nativeAppLinkOpenPersistent or wait for an inbound link".to_string(),
            );
            return -1;
        }
        Err(e) => {
            rns::set_error(e);
            return -1;
        }
    };
    if link_handle.status() != reticulum_rust::link::STATE_ACTIVE {
        rns::set_error(format!(
            "app-link not active (status={}) — wait for ACTIVE before requesting",
            link_handle.status()
        ));
        return -1;
    }

    // Per-call JavaVM + GlobalRef so multiple in-flight requests don't
    // clobber each other.  The single-fire latch ensures we drop the
    // global ref exactly once even if response/failed/timeout race.
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            rns::set_error(format!("Failed to get JavaVM: {}", e));
            return -1;
        }
    };
    let global_ref = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("Failed to create global ref: {}", e));
            return -1;
        }
    };

    // Wrap (jvm, ref) in Arc<Mutex<Option<...>>> so each terminal callback
    // can take() it: whichever runs first invokes onResult and drops the
    // ref; later callers see None and no-op.  This avoids leaking the
    // GlobalRef when response/failed/timeout race.
    let cb_state: Arc<Mutex<Option<(JavaVM, GlobalRef)>>> =
        Arc::new(Mutex::new(Some((jvm, global_ref))));
    let fired = Arc::new(AtomicBool::new(false));

    fn fire(state: &Arc<Mutex<Option<(JavaVM, GlobalRef)>>>, status: i32, bytes: Option<&[u8]>) {
        let taken = state.lock().unwrap().take();
        let (jvm, cb_ref) = match taken {
            Some(pair) => pair,
            None => return,
        };
        let mut env = match jvm.attach_current_thread() {
            Ok(env) => env,
            Err(_) => return,
        };
        let j_bytes = match bytes {
            Some(b) => match env.byte_array_from_slice(b) {
                Ok(arr) => JObject::from(arr),
                Err(_) => JObject::null(),
            },
            None => JObject::null(),
        };
        let _ = env.call_method(
            cb_ref.as_obj(),
            "onResult",
            "(I[B)V",
            &[JValue::Int(status), JValue::Object(&j_bytes)],
        );
    }

    let state_ok = cb_state.clone();
    let fired_ok = fired.clone();
    let response_cb: Arc<dyn Fn(reticulum_rust::link::RequestReceipt) + Send + Sync> =
        Arc::new(move |receipt: reticulum_rust::link::RequestReceipt| {
            if fired_ok.swap(true, Ordering::SeqCst) {
                return;
            }
            match receipt.response {
                Some(ref data) => fire(&state_ok, 0, Some(data)),
                None => fire(&state_ok, 2, None),
            }
        });

    let state_fail = cb_state.clone();
    let fired_fail = fired.clone();
    let failed_cb: Arc<dyn Fn(reticulum_rust::link::RequestReceipt) + Send + Sync> =
        Arc::new(move |_receipt| {
            if fired_fail.swap(true, Ordering::SeqCst) {
                return;
            }
            fire(&state_fail, 2, None);
        });

    // Off-load the synchronous link.request() onto a worker thread to
    // avoid priority inversion on a cooperative caller (mirrors the
    // pattern in LXMF-rust/src/cffi.rs::lxmf_app_link_request_async).
    // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
    let state_send_err = cb_state.clone();
    let fired_send_err = fired.clone();
    let link_for_send = link_handle.clone();
    std::thread::spawn(move || {
        if let Err(e) = link_for_send.request(
            path_str,
            data,
            Some(response_cb),
            Some(failed_cb),
            None,
        ) {
            if !fired_send_err.swap(true, Ordering::SeqCst) {
                fire(&state_send_err, 2, None);
            }
            rns::set_error(format!("link.request failed: {:?}", e));
        }
    });

    // Detached timeout watcher.
    let state_to = cb_state.clone();
    let fired_to = fired.clone();
    let to_secs = timeout_secs.max(0.0);
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_secs_f64(to_secs));
        if !fired_to.swap(true, Ordering::SeqCst) {
            fire(&state_to, 1, None);
        }
    });

    0
}

// ---------------------------------------------------------------------------
// Distro — one LXMF identity shared by all of a person's devices (RFed SPEC §17)
//
// Thin wrappers over `lxmf_rust::distro`, the same helpers the iOS FFI wraps
// (Retichat-ios/rust/retichat-ffi/src/lib.rs `retichat_distro_*`). Kotlin
// holds the distro identity as an ordinary identity handle from
// `identityFromBytes` on the 64-byte private key.
// ---------------------------------------------------------------------------

fn distro_identity(handle: jlong, label: &str) -> Option<Identity> {
    match rns::get_handle::<Identity>(handle as u64) {
        Some(id) => Some(id),
        None => {
            rns::set_error(format!("invalid {label} identity handle"));
            None
        }
    }
}

fn bytes_or_null(env: &JNIEnv, r: Result<Vec<u8>, String>) -> jbyteArray {
    match r {
        Ok(bytes) => vec_to_jbytes(env, &bytes),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

/// `RetichatBridge.nativeDistroGenerate(): ByteArray?` — a fresh 64-byte
/// private key (X25519 || Ed25519), the same layout the web client and iOS
/// export as 128 hex characters.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeDistroGenerate(
    env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    let identity = Identity::new(true);
    bytes_or_null(&env, identity.get_private_key())
}

/// `RetichatBridge.nativeDistroPrivateKey(handle: Long): ByteArray?` — the
/// 64-byte private key behind an identity handle (export / transfer).
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeDistroPrivateKey(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    let Some(identity) = distro_identity(handle, "distro") else { return std::ptr::null_mut() };
    bytes_or_null(&env, identity.get_private_key())
}

/// `RetichatBridge.nativeDistroDeliveryHash(handle: Long): ByteArray?` — the
/// distro's `lxmf.delivery` hash: the address senders use.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeDistroDeliveryHash(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    let Some(identity) = distro_identity(handle, "distro") else { return std::ptr::null_mut() };
    bytes_or_null(&env, lxmf_rust::distro::delivery_hash(&identity))
}

/// `RetichatBridge.nativeDistroRegisterPayload(deviceHandle: Long, distroHandle: Long): ByteArray?`
/// msgpack `[device_pubkey, distro_pubkey, sig_distro(device_pubkey)]`, the
/// body of `/rfed/distro/register` and `/rfed/distro/unregister`.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeDistroRegisterPayload(
    env: JNIEnv,
    _class: JClass,
    device_handle: jlong,
    distro_handle: jlong,
) -> jbyteArray {
    let Some(device) = distro_identity(device_handle, "device") else { return std::ptr::null_mut() };
    let Some(distro) = distro_identity(distro_handle, "distro") else { return std::ptr::null_mut() };
    bytes_or_null(&env, lxmf_rust::distro::register_payload(&device, &distro))
}

/// `RetichatBridge.nativeDistroListPayload(distroHandle: Long): ByteArray?`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeDistroListPayload(
    env: JNIEnv,
    _class: JClass,
    distro_handle: jlong,
) -> jbyteArray {
    let Some(distro) = distro_identity(distro_handle, "distro") else { return std::ptr::null_mut() };
    bytes_or_null(&env, lxmf_rust::distro::list_payload(&distro))
}

/// `RetichatBridge.nativeDistroAnnouncePayload(distroHandle: Long, announceName: ByteArray?): ByteArray?`
/// The pre-signed `lxmf.delivery` announce RFed replays on the distro's
/// behalf: msgpack `[flags|announce_data, distro_pubkey, sig_distro(value)]`.
///
/// `announceName` is the raw UTF-8 Announce Display Name (DISPLAY_NAMES.md
/// §2.2), null/empty for none: the app_data is `[name | nil, nil, [0xD0]]`,
/// the name cleaned with the announce rules. (This argument used to be
/// caller app_data; every caller passed null, which still means "no name".)
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeDistroAnnouncePayload(
    env: JNIEnv,
    _class: JClass,
    distro_handle: jlong,
    announce_name: JByteArray,
) -> jbyteArray {
    let Some(distro) = distro_identity(distro_handle, "distro") else { return std::ptr::null_mut() };
    let name: Option<Vec<u8>> = if announce_name.is_null() {
        None
    } else {
        let v = jbytes_to_vec(&env, &announce_name);
        if v.is_empty() { None } else { Some(v) }
    };
    bytes_or_null(&env, lxmf_rust::distro::announce_payload(&distro, name.as_deref()))
}

/// `RetichatBridge.nativeDistroUnwrap(distroHandle: Long, blob: ByteArray): String?`
/// Decrypt a fan-out blob (`distro_lxmf_hash(16) | lxmf_blob`) with the
/// distro identity. Returns the JSON `lxmf_rust::distro::DistroMessage::to_json`
/// builds, the same object iOS returns (`source_hash`, `timestamp`, `title`,
/// `content`, `is_delivery_notification`, `ticket`, `distro_transfer_key`,
/// `sent_to`, `sent_by`, `display_name_state`, `display_name`,
/// `signature_validated`, `unverified_reason`, `fields`), an empty string when the
/// blob is addressed to a different distro, or null on error. `sent_to` /
/// `sent_by` are the RFed SPEC §17.11 sent-message sync marker (null unless
/// the message is a sync copy; a non-null `sent_by` with a null `sent_to`
/// is a copy with a malformed 0xFC). `display_name_state` is 0 absent,
/// 1 clear, 2 name; whether to accept it depends on `signature_validated` /
/// `unverified_reason` (0 ok, 1 source unknown, 2 invalid) — DISPLAY_NAMES.md §5.2.
/// `fields` is the message's LXMF fields map (attachments included) as the
/// sender packed it, in standard padded base64, or null when it had none;
/// RfedDistroClient decodes it with DistroCodec.fieldsBytes.
///
/// RFed SPEC §17.14 (GROUPS-DISTRO-DESIGN.md §7): also `hash` (64 hex),
/// `has_group_id`, `group_id`, `group_action`, `group_name`, `group_sync` (null or
/// an object), `sender_key` (128 hex or null) and `packed` (base64 or null). This
/// crate's group receipt (`group_status::receipt_parts`) runs on the rebuilt
/// message, so `signature_validated` / `unverified_reason` are its verdict: an
/// accept or leave from a source whose key is not held is decided under the key
/// it carries. The JSON is `DistroMessage::to_json_with_receipt`, the one
/// definition both bridges use.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeDistroUnwrap(
    mut env: JNIEnv,
    _class: JClass,
    distro_handle: jlong,
    blob: JByteArray,
) -> jstring {
    let Some(mut distro) = distro_identity(distro_handle, "distro") else { return std::ptr::null_mut() };
    let data = jbytes_to_vec(&env, &blob);
    let json = match group_status::distro_unwrap_json(&mut distro, &data) {
        Ok(None) => String::new(),
        Ok(Some(json)) => json,
        Err(e) => {
            rns::set_error(e);
            return std::ptr::null_mut();
        }
    };
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            rns::set_error(format!("jstring: {e}"));
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// What a device owes its distro (RFed SPEC §17.12 "On the sending device", §17.11)
//
// The Kotlin outbox (service/DistroOutbox.kt) keeps what is owed and decides
// when to upload. These wrappers give it what it cannot make itself: C's
// fields, a message packed once, an upload built for a distro's key, the
// propagation link as it is now and as it changes, and an upload on the held
// link that says when the node proves it and when it is lost. The pure parts
// are in `distro_outbox.rs`. Nothing here sends on a timer or sends again.
// ---------------------------------------------------------------------------

use app_links::{link_representation, AppLinks, LinkRepresentation, ResourceSilenceWatch};
use reticulum_rust::link::{LinkHandle, STATE_ACTIVE};

/// Call a no-argument Kotlin method on a callback from whatever thread this
/// runs on. A Kotlin exception must not stay pending on a Rust thread: it is
/// described in logcat and cleared.
fn call_kotlin(jvm: &JavaVM, callback: &GlobalRef, method: &str, sig: &str, args: &[JValue]) {
    let mut env = match jvm.attach_current_thread() {
        Ok(env) => env,
        Err(_) => return,
    };
    let _ = env.call_method(callback.as_obj(), method, sig, args);
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
}

/// `RetichatBridge.nativeMessageSetDistroChannelSync(handle: Long, op: String,
/// name: String, atMs: Long, deviceHex: String): Int`
///
/// RFed SPEC §17.12: write the membership message C's fields on the outbound
/// message `handle` (0x0C an empty bin, 0xFB `"rfed.distro.channel"`, 0xFC the
/// native array `[op, name, at_ms]`, 0xFD the sending device's own address).
/// 0 on success, -1 with `nativeLastError` and the message unchanged.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageSetDistroChannelSync(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    op: JString,
    name: JString,
    at_ms: jlong,
    device_hex: JString,
) -> jint {
    let op = jstring_to_string(&mut env, &op);
    let name = jstring_to_string(&mut env, &name);
    let device = jstring_to_string(&mut env, &device_hex);
    if at_ms < 0 {
        rns::set_error(format!("at_ms {at_ms} is negative"));
        return -1;
    }
    let Some(msg) = rns::get_handle::<Arc<Mutex<lxmf_rust::lx_message::LXMessage>>>(handle as u64) else {
        rns::set_error("invalid message handle".to_string());
        return -1;
    };
    let result = match msg.lock() {
        Ok(mut m) => distro_outbox::set_channel_sync_fields(&mut m, &op, &name, at_ms as u64, &device),
        Err(e) => Err(e.to_string()),
    };
    ok_or_neg(result)
}

/// `RetichatBridge.nativeMessageSetDistroGroupSync(handle: Long, syncJson: String,
/// dHex: String, deviceHex: String): Int`
///
/// RFed SPEC §17.14: write the group sync G's fields on the outbound message
/// `handle`, from D to D (0x0C an empty bin, 0xFB `"rfed.distro.group"`, 0xFC
/// the op's native array, 0xFD the sending device's own address). `syncJson`
/// is the unwrap's `group_sync` object without `by` and `problem`
/// (`lxmf_rust::distro::GroupSync::from_json`); `dHex` is D's `lxmf.delivery`
/// address. 0 on success, -1 with `nativeLastError` and the message unchanged
/// (`distro_outbox::set_group_sync_json`).
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageSetDistroGroupSync(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    sync_json: JString,
    d_hex: JString,
    device_hex: JString,
) -> jint {
    let sync_json = jstring_to_string(&mut env, &sync_json);
    let d_hex = jstring_to_string(&mut env, &d_hex);
    let device = jstring_to_string(&mut env, &device_hex);
    let Some(msg) = rns::get_handle::<Arc<Mutex<lxmf_rust::lx_message::LXMessage>>>(handle as u64) else {
        rns::set_error("invalid message handle".to_string());
        return -1;
    };
    let result = match msg.lock() {
        Ok(mut m) => distro_outbox::set_group_sync_json(&mut m, &sync_json, &d_hex, &device),
        Err(e) => Err(e.to_string()),
    };
    ok_or_neg(result)
}

/// `RetichatBridge.nativeMessagePack(handle: Long): ByteArray?`
///
/// Pack and sign the outbound message once and return its packed bytes
/// (`dest | src | signature | payload`), which the outbox keeps and uploads as
/// they are, never packed again. The message's hash (`nativeMessageGetHash`) is
/// then set. Null with `nativeLastError` when it cannot be packed.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessagePack(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    let Some(msg) = rns::get_handle::<Arc<Mutex<lxmf_rust::lx_message::LXMessage>>>(handle as u64) else {
        rns::set_error("invalid message handle".to_string());
        return std::ptr::null_mut();
    };
    bytes_or_null(&env, distro_outbox::pack_message(&msg))
}

/// `RetichatBridge.nativePropagationBuildUpload(distroPublicKey: ByteArray,
/// packed: ByteArray, node: ByteArray): ByteArray?`
///
/// Build the propagation upload of the packed message `packed` for the node
/// `node`: encrypted to `distroPublicKey` and no other key, stamped for the
/// node's stamp cost (read from its announce; unknown is an error, and the
/// node's path is requested so its announce can be heard). Blocks while the
/// stamp is mined: call it off the main thread. Null with `nativeLastError`
/// when it cannot be built, with nothing sent.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePropagationBuildUpload(
    env: JNIEnv,
    _class: JClass,
    distro_public_key: JByteArray,
    packed: JByteArray,
    node: JByteArray,
) -> jbyteArray {
    let key = jbytes_to_vec(&env, &distro_public_key);
    let packed = jbytes_to_vec(&env, &packed);
    let node = jbytes_to_vec(&env, &node);
    let Some(cost) = node_stamp_cost_or_ask(&node) else { return std::ptr::null_mut() };
    bytes_or_null(&env, distro_outbox::build_propagation_upload(&key, &packed, cost))
}

/// The stamp cost `node` announced. Unknown: as the router does when it has no
/// cost (LXMRouter::get_outbound_propagation_cost), ask for the announce that
/// carries it, set `nativeLastError`, and return `None`; the upload waits for
/// the next coming-up.
fn node_stamp_cost_or_ask(node: &[u8]) -> Option<u32> {
    match distro_outbox::node_stamp_cost(Identity::recall_app_data(node).as_deref()) {
        Ok(cost) => Some(cost),
        Err(e) => {
            Transport::request_path(node, None, None, None, None);
            rns::set_error(e);
            None
        }
    }
}

/// `RetichatBridge.nativeDistroSeal(distroHandle: Long, packed: ByteArray): ByteArray?`
///
/// The distro sync proof (RFed SPEC §17.13; DISTRO-SYNC-PROOF-DESIGN.md §5.1):
/// seal the message `packed` (D's own, `nativeMessagePack`) once, when it is
/// owed, with the distro identity `distroHandle`. Returns `sig(64) | sealed`,
/// where `sealed = D_hash | D.encrypt(packed[16..])` and `sig` is D's signature
/// over `"rfed.distro.sync" | 0x01 | D_hash | SHA-256(sealed)`
/// (`lxmf_rust::distro::seal_for_sync`). Null with `nativeLastError` when the
/// handle has no private key, `packed` is not addressed to it, or the signature
/// does not validate: the outbox then owes the entry without them.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeDistroSeal(
    env: JNIEnv,
    _class: JClass,
    distro_handle: jlong,
    packed: JByteArray,
) -> jbyteArray {
    let Some(distro) = distro_identity(distro_handle, "distro") else { return std::ptr::null_mut() };
    let packed = jbytes_to_vec(&env, &packed);
    bytes_or_null(&env, distro_outbox::seal(&distro, &packed))
}

/// `RetichatBridge.nativePropagationBuildSealedUpload(distroPublicKey: ByteArray,
/// sealed: ByteArray, sig: ByteArray, withClaim: Boolean, node: ByteArray): ByteArray?`
///
/// One upload of a sealed entry (DISTRO-SYNC-PROOF-DESIGN.md §5.2): the stored
/// `sealed` bytes, never encrypted again, with a PN stamp mined over
/// `SHA-256(sealed)` for the node's stamp cost (read from its announce as
/// `nativePropagationBuildUpload` reads it; unknown is an error, and the node's
/// path is requested), and the claim `[id, distroPublicKey, sig]` as the third
/// element only when `withClaim` (Kotlin's gate: `node` is the RFed node's own
/// `lxmf.propagation`, §5.4). A claim RFed would refuse is left out and said as
/// an error; the message still goes. Blocks while the stamp is mined: call it
/// off the main thread. Null with `nativeLastError` when it cannot be built,
/// with nothing sent.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePropagationBuildSealedUpload(
    env: JNIEnv,
    _class: JClass,
    distro_public_key: JByteArray,
    sealed: JByteArray,
    sig: JByteArray,
    with_claim: jboolean,
    node: JByteArray,
) -> jbyteArray {
    let key = jbytes_to_vec(&env, &distro_public_key);
    let sealed = jbytes_to_vec(&env, &sealed);
    let sig = jbytes_to_vec(&env, &sig);
    let node = jbytes_to_vec(&env, &node);
    let Some(cost) = node_stamp_cost_or_ask(&node) else { return std::ptr::null_mut() };
    let built = distro_outbox::build_sealed_upload(&key, &sealed, &sig, with_claim != 0, cost).map(|built| {
        if let Some(why) = &built.claim_refused {
            // Never silent: the upload goes without the proof, so its devices are woken as before.
            reticulum_rust::log(
                format!(
                    "[distro-sync] the stored sync claim for {} is not one RFed would accept ({why}): \
                     the upload goes without it, and wakes the distro's devices as before",
                    reticulum_rust::hexrep(&sealed[..16.min(sealed.len())], false),
                ),
                reticulum_rust::LOG_ERROR,
                false,
                false,
            );
        }
        built.upload
    });
    bytes_or_null(&env, built)
}

/// `RetichatBridge.nativePropagationLink(node: ByteArray): ByteArray?`
///
/// The outbound link AppLinks holds for the propagation node, as it is now:
/// `[state u8 | link id]`, where state is the link's own (0 pending, 1
/// handshake, 2 ACTIVE, 3 STALE, 4 closed). Null when none is held. A read of
/// the registry, never a wait.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePropagationLink(
    env: JNIEnv,
    _class: JClass,
    node: JByteArray,
) -> jbyteArray {
    let node = jbytes_to_vec(&env, &node);
    match AppLinks::get_handle(&node) {
        Some(link) => {
            let mut out = vec![link.status()];
            out.extend_from_slice(&link.link_id());
            vec_to_jbytes(&env, &out)
        }
        None => std::ptr::null_mut(),
    }
}

/// What reports an upload to Kotlin's `PropagationUploadCallback`: each of
/// `onProved`, `onLost` and `onNeverLeft` at most once, from whatever thread the
/// stack decides it on. A proof can follow a loss (the receipt stays listed, and
/// a late proof still concludes it): both are told, in the order they happen.
/// A never-left is told instead of a loss, never with one (app-links'
/// `send_on_held_link_owed` decides an upload once).
struct UploadReport {
    jvm: JavaVM,
    callback: GlobalRef,
    proved: AtomicBool,
    lost: AtomicBool,
    never_left: AtomicBool,
}

impl UploadReport {
    fn proved(&self) {
        if !self.proved.swap(true, Ordering::SeqCst) {
            call_kotlin(&self.jvm, &self.callback, "onProved", "()V", &[]);
        }
    }

    fn lost(&self) {
        if !self.lost.swap(true, Ordering::SeqCst) {
            call_kotlin(&self.jvm, &self.callback, "onLost", "()V", &[]);
        }
    }

    fn never_left(&self) {
        if !self.never_left.swap(true, Ordering::SeqCst) {
            call_kotlin(&self.jvm, &self.callback, "onNeverLeft", "()V", &[]);
        }
    }
}

/// The receipt's timeout tears the propagation link down (LXMF/LXMessage.py
/// `__link_packet_timed_out`: `packet_receipt.destination.teardown()`), and the
/// phones do the same (DESIGN_PRINCIPLES §3, what a device owes its distro).
/// It is the link the upload went on that is torn down. That link, while it is
/// still the one AppLinks holds, goes through `teardown_held_link`, which keeps
/// the registration so the persistent link re-opens under its normal
/// close-triggered policy: the new link's establishment is what carries what is
/// owed. A link already replaced by a newer one is torn down alone, and the
/// newer link, which did nothing wrong, is left up.
fn tear_down_link_of_upload(node: &[u8], link: &LinkHandle) {
    match AppLinks::get_handle(node) {
        Some(held) if held.same_link(link) => {
            AppLinks::teardown_held_link(node);
        }
        _ => link.teardown(),
    }
}

/// `RetichatBridge.nativeInterfaceUpEdges(): Long`
///
/// How many interface up-edges Transport has told (`Transport::interface_up_edges`),
/// the count `app_links::UpEdgeMark` wraps. The owner of a never-left upload is
/// the Kotlin outbox, so it takes the mark there, just before each
/// `nativePropagationUpload`, and asks it again once it has recorded a never-left
/// upload as owed: a different count means an interface came up since, and that
/// up-edge, which its listener heard before the upload was owed, was this
/// upload's (`AppLinks::send_on_held_link_owed`, steps 1 and 2). A read, never a wait.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeInterfaceUpEdges(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    Transport::interface_up_edges() as jlong
}

/// `RetichatBridge.nativePropagationUpload(node: ByteArray, upload: ByteArray,
/// callback: PropagationUploadCallback): ByteArray?`
///
/// Hand `upload` (a `nativePropagationBuildUpload` or
/// `nativePropagationBuildSealedUpload` result) to the link AppLinks holds for
/// the propagation node, which must be ACTIVE, as an upload the outbox owes
/// (`AppLinks::send_on_held_link_owed`): one packet, or a Resource over the link
/// MDU. Returns `[how u8 | link id]` (how 1 a packet, 2 a Resource; the link id
/// the upload went on), or null with `nativeLastError` when it did not leave (no
/// active link held, or the packet could not be queued): then no callback is
/// ever called.
///
/// Once it has left, `callback.onProved()` is called when the node proves the
/// packet (or the Resource completes), and `callback.onLost()` when the packet
/// receipt times out or the Resource fails. Nothing here races, resends or
/// times anything: the stack's own receipt timeout is the clock. On a loss the
/// link the upload went on is torn down (`tear_down_link_of_upload`) before
/// `onLost` is called, so the close that follows is what the loss is.
///
/// A Resource whose first advertisement no interface could carry never left
/// the device (DESIGN_PRINCIPLES §3, James 2026-10-06; the Resource case
/// 2026-10-10): app-links cancels it locally, and `callback.onNeverLeft()` is
/// called instead of `onLost`, with no teardown. The link is kept, and the
/// outbox records it as it records a null return here: it goes at the next
/// interface up-edge or coming-up.
///
/// DESIGN_PRINCIPLES §1, bulk transfers: a Resource's progress feeds a
/// `ResourceSilenceWatch`, which logs (and in a debug build asserts, at its
/// end point) a silence over 5 s and decides nothing. Its end point, `finish`,
/// is the last thing each outcome does: after the delivery, the loss or the
/// never-left is reported, and after an `Err`.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePropagationUpload(
    env: JNIEnv,
    _class: JClass,
    node: JByteArray,
    upload: JByteArray,
    callback: JObject,
) -> jbyteArray {
    let node = jbytes_to_vec(&env, &node);
    let data = jbytes_to_vec(&env, &upload);
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => { rns::set_error(format!("Failed to get JavaVM: {e}")); return std::ptr::null_mut(); }
    };
    let callback = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => { rns::set_error(format!("Failed to create global ref: {e}")); return std::ptr::null_mut(); }
    };
    let Some(before) = AppLinks::get_handle(&node).filter(|link| link.status() == STATE_ACTIVE) else {
        rns::set_error("no active link is held for the propagation node".to_string());
        return std::ptr::null_mut();
    };
    let how: u8 = match link_representation(data.len()) {
        LinkRepresentation::Packet => 1,
        LinkRepresentation::Resource => 2,
    };

    let report = Arc::new(UploadReport {
        jvm,
        callback,
        proved: AtomicBool::new(false),
        lost: AtomicBool::new(false),
        never_left: AtomicBool::new(false),
    });
    // The link the upload goes on: `before`, unless the held link changed
    // between reading it and the send (a replacement is then the newer one).
    let used: Arc<Mutex<LinkHandle>> = Arc::new(Mutex::new(before.clone()));
    // §1, bulk transfers: fed by the Resource's own progress; a packet reports none.
    let watch = ResourceSilenceWatch::new(format!(
        "distro outbox upload of {} B to propagation node {}",
        data.len(),
        reticulum_rust::hexrep(&node, false),
    ));

    // The watch's end point (`finish`) comes after this owner has reported the
    // outcome, not after it has recorded it: each report hands it to Kotlin,
    // which records it later on the outbox's serial executor. That is as
    // app-links asks for an owner that hands the outcome on. A debug build's
    // §1 panic, which a release .so never makes, ends only its own thread
    // under this crate's default panic = "unwind". Under panic = "abort" it
    // would end the process before that record; storage still holds the
    // entry owed (a proof is recorded by taking it out), so it is sent
    // again on the next run and nothing is lost.
    let proved_report = report.clone();
    let proved_watch = watch.clone();
    let on_delivered: Arc<dyn Fn() + Send + Sync + 'static> = Arc::new(move || {
        proved_watch.delivered();
        proved_report.proved();
        // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1 (the watch's end point, last)
        let _ = proved_watch.finish();
    });
    let lost_report = report.clone();
    let lost_node = node.clone();
    let lost_used = used.clone();
    let lost_watch = watch.clone();
    let on_failed: Arc<dyn Fn() + Send + Sync + 'static> = Arc::new(move || {
        let link = lost_used.lock().map(|l| l.clone());
        if let Ok(link) = link {
            tear_down_link_of_upload(&lost_node, &link);
        }
        lost_report.lost();
        // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1 (the watch's end point, last)
        let _ = lost_watch.finish();
    });
    // Nothing left: no teardown, the link is kept (DESIGN_PRINCIPLES §3, 2026-10-06/10-10).
    let never_left_report = report.clone();
    let never_left_watch = watch.clone();
    let on_never_left: Arc<dyn Fn() + Send + Sync + 'static> = Arc::new(move || {
        never_left_report.never_left();
        // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1 (the watch's end point, last)
        let _ = never_left_watch.finish();
    });

    match AppLinks::send_on_held_link_owed(&node, data, true, on_delivered, on_failed, Some(watch.on_progress()), on_never_left) {
        Ok(()) => {
            let link = match AppLinks::get_handle(&node) {
                Some(after) if !after.same_link(&before) => after,
                _ => before,
            };
            if let Ok(mut slot) = used.lock() {
                *slot = link.clone();
            }
            let mut out = vec![how];
            out.extend_from_slice(&link.link_id());
            vec_to_jbytes(&env, &out)
        }
        Err(e) => {
            rns::set_error(e);
            // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1 (the watch's end point, last)
            let _ = watch.finish();
            std::ptr::null_mut()
        }
    }
}

/// `RetichatBridge.nativePropagationLinkEventsRegister(callback: PropagationLinkCallback): Int`
///
/// Register a second AppLinks status callback that also says which link an
/// event is about: `callback.onLink(destHash, status, linkId)` for every status
/// change of every app-link (0 none, 1 path requested, 2 establishing, 3 ACTIVE,
/// 4 disconnected, 5 recovered), with the link's id for establishing, ACTIVE and
/// recovered and null otherwise. A new propagation link's establishment is its
/// ACTIVE event; a STALE link's recovery (it heard from its peer again) is its
/// RECOVERED event, an event only (`AppLinks::status` never returns it), told
/// once per recovery and only for the link AppLinks still holds.
/// The existing `nativeAppLinkRegisterStatusCallback` is left as it is and is
/// told the same statuses. The stack's shutdown drops every callback, so
/// register again after each start.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePropagationLinkEventsRegister(
    env: JNIEnv,
    _class: JClass,
    callback: JObject,
) -> jint {
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => { rns::set_error(format!("Failed to get JavaVM: {e}")); return -1; }
    };
    let callback = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => { rns::set_error(format!("Failed to create global ref: {e}")); return -1; }
    };
    AppLinks::register_status_callback(Arc::new(
        move |dest: &[u8], status: u8, link: Option<LinkHandle>| {
            let mut env = match jvm.attach_current_thread() {
                Ok(env) => env,
                Err(_) => return,
            };
            let j_dest = match env.byte_array_from_slice(dest) {
                Ok(d) => d,
                Err(_) => return,
            };
            let j_link = match link {
                Some(link) => match env.byte_array_from_slice(&link.link_id()) {
                    Ok(id) => JObject::from(id),
                    Err(_) => return,
                },
                None => JObject::null(),
            };
            let _ = env.call_method(
                callback.as_obj(),
                "onLink",
                "([BI[B)V",
                &[JValue::Object(&j_dest), JValue::Int(status as jint), JValue::Object(&j_link)],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
        },
    ));
    0
}

/// `RetichatBridge.nativeInterfaceOnlineRegister(callback: InterfaceOnlineCallback): Int`
///
/// Tell `callback.onInterfaceOnline(name)` whenever a Reticulum interface comes
/// online: Transport's up-edge (`set_interface_online` false to true), told once
/// per edge with the interface's name, the same edge AppLinks re-attempts its
/// links on. It is how the distro outbox hears that an interface it needs is
/// back (James, 2026-10-06): an upload that never left the device, because no
/// interface could carry it, is no failure and goes then. One callback at a
/// time; a later register replaces it. Nothing here waits, polls or times.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeInterfaceOnlineRegister(
    env: JNIEnv,
    _class: JClass,
    callback: JObject,
) -> jint {
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => { rns::set_error(format!("Failed to get JavaVM: {e}")); return -1; }
    };
    let callback = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => { rns::set_error(format!("Failed to create global ref: {e}")); return -1; }
    };
    distro_outbox::INTERFACE_UP.set(Arc::new(move |name: &str| {
        let mut env = match jvm.attach_current_thread() {
            Ok(env) => env,
            Err(_) => return,
        };
        let j_name = match env.new_string(name) {
            Ok(n) => n,
            Err(_) => return,
        };
        let _ = env.call_method(
            callback.as_obj(),
            "onInterfaceOnline", "(Ljava/lang/String;)V",
            &[JValue::Object(&j_name)],
        );
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }
    }));
    distro_outbox::hook_interface_up();
    0
}

/// `RetichatBridge.nativeInterfaceOnlineUnregister(): Int`
///
/// Stop telling the callback `nativeInterfaceOnlineRegister` set (the stack is
/// stopping). Transport keeps its one listener, which then tells no one.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeInterfaceOnlineUnregister(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    distro_outbox::INTERFACE_UP.clear();
    0
}

// ---------------------------------------------------------------------------
// RTNode over Bluetooth (Reticulum-rust interfaces::prns_ble, central only)
// ---------------------------------------------------------------------------
//
// Kotlin (service/RTNodeBluetooth.kt) is the radio: it scans when asked,
// reports each advertisement of the Prns service, connects when handed a
// link, sets the GATT link up, performs the writes it is asked for and
// reports every event. Rust decides what to dial and does the protocol.

use reticulum_rust::interfaces::prns_ble;

/// The Kotlin `PrnsBleCallback`. Called on the engine's threads and on the
/// callers' (Binder threads, interface writers), so each call attaches.
struct JniBleHost {
    jvm: JavaVM,
    callback: GlobalRef,
}

impl JniBleHost {
    /// A Kotlin exception must not stay pending on a thread that calls back
    /// into Java later.
    fn clear_exception(env: &mut JNIEnv) {
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }
    }
}

impl prns_ble::Host for JniBleHost {
    fn scan(&self, on: bool) {
        let Ok(mut env) = self.jvm.attach_current_thread() else { return };
        let _ = env.call_method(self.callback.as_obj(), "onScan", "(Z)V", &[JValue::Bool(on as u8)]);
        Self::clear_exception(&mut env);
    }

    fn write(&self, link: u64, characteristic: prns_ble::Characteristic, bytes: &[u8]) {
        let Ok(mut env) = self.jvm.attach_current_thread() else { return };
        let Ok(data) = env.byte_array_from_slice(bytes) else { return };
        let _ = env.call_method(
            self.callback.as_obj(),
            "onWrite",
            "(JI[B)V",
            &[JValue::Long(link as i64), JValue::Int(characteristic as i32), JValue::Object(&data)],
        );
        Self::clear_exception(&mut env);
    }

    fn disconnect(&self, link: u64) {
        let Ok(mut env) = self.jvm.attach_current_thread() else { return };
        let _ = env.call_method(self.callback.as_obj(), "onDisconnect", "(J)V", &[JValue::Long(link as i64)]);
        Self::clear_exception(&mut env);
    }

    fn link_state(&self, link: u64, state: prns_ble::LinkState, peer: Option<&[u8; 16]>, interface: Option<&str>) {
        let Ok(mut env) = self.jvm.attach_current_thread() else { return };
        let peer_obj: JObject = match peer.map(|p| env.byte_array_from_slice(p)) {
            Some(Ok(array)) => array.into(),
            _ => JObject::null(),
        };
        let name_obj: JObject = match interface.map(|n| env.new_string(n)) {
            Some(Ok(name)) => name.into(),
            _ => JObject::null(),
        };
        let _ = env.call_method(
            self.callback.as_obj(),
            "onLinkState",
            "(JI[BLjava/lang/String;)V",
            &[JValue::Long(link as i64), JValue::Int(state as i32), JValue::Object(&peer_obj), JValue::Object(&name_obj)],
        );
        Self::clear_exception(&mut env);
    }
}

/// `RetichatBridge.nativePrnsBleStart(storageDir: String, callback: PrnsBleCallback): ByteArray?`
/// The phone's 16-byte Bluetooth identity, or null (see lastError). Call
/// once the stack runs and the delivery destination is published.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePrnsBleStart(
    mut env: JNIEnv,
    _class: JClass,
    storage_dir: JString,
    callback: JObject,
) -> jbyteArray {
    let dir = jstring_to_string(&mut env, &storage_dir);
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            rns::set_error(format!("Failed to get JavaVM: {e}"));
            return std::ptr::null_mut();
        }
    };
    let callback = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("Failed to create global ref: {e}"));
            return std::ptr::null_mut();
        }
    };
    let host = Arc::new(JniBleHost { jvm, callback });
    match prns_ble::start(std::path::Path::new(&dir), prns_ble::Endpoint::ANDROID, host) {
        Ok(identity) => vec_to_jbytes(&env, &identity),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

/// `RetichatBridge.nativePrnsBleStop(): Int` — closes every link and removes
/// the RTNodes' interfaces. Blocks briefly: never on the main thread, never
/// from a PrnsBleCallback, and before the stack shuts down.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePrnsBleStop(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    prns_ble::stop();
    0
}

/// `RetichatBridge.nativePrnsBleSighted(address: String, companyId: Int, data: ByteArray?): Long`
/// An advertisement of the Prns service: the device address and its
/// manufacturer data for `companyId` (ScanRecord.getManufacturerSpecificData).
/// The link to dial it on, or 0.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePrnsBleSighted(
    mut env: JNIEnv,
    _class: JClass,
    address: JString,
    company_id: jint,
    data: JByteArray,
) -> jlong {
    if data.is_null() {
        return 0;
    }
    let address = jstring_to_string(&mut env, &address);
    let data = jbytes_to_vec(&env, &data);
    match prns_ble::sighted(&address, company_id as u16, &data) {
        Ok(Some(link)) => link as jlong,
        Ok(None) => 0,
        Err(e) => {
            rns::set_error(e);
            0
        }
    }
}

/// `RetichatBridge.nativePrnsBleLinkReady(link: Long, maxWriteLen: Int): Int`
/// Connected, services discovered, both characteristics subscribed.
/// maxWriteLen: the negotiated ATT MTU - 3.
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePrnsBleLinkReady(
    _env: JNIEnv,
    _class: JClass,
    link: jlong,
    max_write_len: jint,
) -> jint {
    ok_or_neg(prns_ble::link_ready(link as u64, max_write_len.max(0) as usize))
}

/// `RetichatBridge.nativePrnsBleLinkReceived(link: Long, characteristic: Int, data: ByteArray): Int`
/// A notification from characteristic 0 (control) or 1 (data).
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePrnsBleLinkReceived(
    env: JNIEnv,
    _class: JClass,
    link: jlong,
    characteristic: jint,
    data: JByteArray,
) -> jint {
    let Some(characteristic) = prns_ble::Characteristic::from_u8(characteristic as u8) else {
        rns::set_error(format!("unknown characteristic {characteristic}"));
        return -1;
    };
    let bytes = jbytes_to_vec(&env, &data);
    ok_or_neg(prns_ble::link_received(link as u64, characteristic, &bytes))
}

/// `RetichatBridge.nativePrnsBleLinkWriteDone(link: Long, ok: Boolean): Int`
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePrnsBleLinkWriteDone(
    _env: JNIEnv,
    _class: JClass,
    link: jlong,
    ok: jni::sys::jboolean,
) -> jint {
    ok_or_neg(prns_ble::link_write_done(link as u64, ok != 0))
}

/// `RetichatBridge.nativePrnsBleLinkClosed(link: Long): Int` — the
/// connection or attempt is gone (disconnect, connect failure, or a failure
/// setting the link up).
#[no_mangle]
pub extern "system" fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePrnsBleLinkClosed(
    _env: JNIEnv,
    _class: JClass,
    link: jlong,
) -> jint {
    ok_or_neg(prns_ble::link_closed(link as u64))
}
