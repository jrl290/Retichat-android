//! JNI bridge for Retichat Android.
//!
//! This crate produces a `cdylib` (`libretichat_jni.so`) loaded via
//! `System.loadLibrary("retichat_jni")` in Kotlin.
//!
//! Every public function follows the JNI naming convention:
//!   Java_com_retichat_app_bridge_RetichatBridge_<methodName>
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

use std::sync::{Arc, Mutex};

use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jbyteArray, jfloat, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};

use lxmf_rust::ffi as lxmf;
use reticulum_rust::ffi as rns;

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

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeInit(configDir: String, logLevel: Int): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeInit(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeShutdown(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    ok_or_neg(rns::shutdown())
}

/// `RetichatBridge.nativeLastError(): String?`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeLastError(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    ok_or_zero(rns::identity_create())
}

/// `RetichatBridge.nativeIdentityFromFile(path: String): Long`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityFromFile(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    let p = jstring_to_string(&mut env, &path);
    ok_or_zero(rns::identity_from_file(&p))
}

/// `RetichatBridge.nativeIdentityFromBytes(bytes: ByteArray): Long`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityFromBytes(
    env: JNIEnv,
    _class: JClass,
    bytes: JByteArray,
) -> jlong {
    let b = jbytes_to_vec(&env, &bytes);
    ok_or_zero(rns::identity_from_bytes(&b))
}

/// `RetichatBridge.nativeIdentityToFile(handle: Long, path: String): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityToFile(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityPublicKey(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityHash(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    ok_or_neg(rns::identity_destroy(handle as u64))
}

// ---- Destination ----

/// `RetichatBridge.nativeDestinationHash(idHandle: Long, appName: String, aspects: String): ByteArray?`
///
/// `aspects` is a comma-separated string, e.g. `"delivery"`.
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeDestinationHash(
    mut env: JNIEnv,
    _class: JClass,
    id_handle: jlong,
    app_name: JString,
    aspects: JString,
) -> jbyteArray {
    let app = jstring_to_string(&mut env, &app_name);
    let asp = jstring_to_string(&mut env, &aspects);
    let parts: Vec<&str> = asp.split(',').map(|s| s.trim()).filter(|s| !s.is_empty()).collect();

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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeTransportHasPath(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    if rns::transport_has_path(&h) { 1 } else { 0 }
}

/// `RetichatBridge.nativeTransportRequestPath(destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeTransportRequestPath(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(rns::transport_request_path(&h))
}

/// `RetichatBridge.nativeTransportHopsTo(destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeTransportHopsTo(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterCreate(
    mut env: JNIEnv,
    _class: JClass,
    identity_handle: jlong,
    storage_path: JString,
) -> jlong {
    let sp = jstring_to_string(&mut env, &storage_path);
    ok_or_zero(lxmf::router_create(identity_handle as u64, &sp))
}

/// `RetichatBridge.nativeRouterRegisterDelivery(router: Long, identity: Long, name: String, stampCost: Int): Long`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterRegisterDelivery(
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

/// `RetichatBridge.nativeRouterSetDeliveryCallback(routerHandle: Long, callback: MessageCallback): Int`
///
/// `MessageCallback` is a Kotlin interface with:
/// ```kotlin
/// fun onMessage(hash: ByteArray, srcHash: ByteArray, destHash: ByteArray,
///               title: String, content: String, timestamp: Double,
///               signatureValid: Boolean)
/// ```
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterSetDeliveryCallback(
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

    let result = lxmf::router_set_delivery_callback(
        router as u64,
        Arc::new(move |msg: lxmf::ReceivedMessage| {
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

            let _ = env.call_method(
                cb_ref.as_obj(),
                "onMessage",
                "([B[B[BLjava/lang/String;Ljava/lang/String;DZ[B)V",
                &[
                    JValue::Object(&j_hash),
                    JValue::Object(&j_src),
                    JValue::Object(&j_dest),
                    JValue::Object(&JObject::from(j_title)),
                    JValue::Object(&JObject::from(j_content)),
                    JValue::Double(msg.timestamp),
                    JValue::Bool(msg.signature_validated as u8),
                    JValue::Object(&j_fields),
                ],
            );
        }),
    );

    ok_or_neg(result)
}

/// `RetichatBridge.nativeRouterSetAnnounceCallback(routerHandle: Long, callback: AnnounceCallback): Int`
///
/// `AnnounceCallback` is a Kotlin interface with:
/// ```kotlin
/// fun onAnnounce(destHash: ByteArray, displayName: String?)
/// ```
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterSetAnnounceCallback(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterAnnounce(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(lxmf::router_announce(router as u64, &h))
}

/// `RetichatBridge.nativeRouterWatchDestination(router: Long, destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterWatchDestination(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(lxmf::router_watch_destination(router as u64, &h))
}

/// `RetichatBridge.nativeRouterProcessOutbound(router: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterProcessOutbound(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jint {
    ok_or_neg(lxmf::router_process_outbound(router as u64))
}

/// `RetichatBridge.nativeRouterDestroy(router: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterDestroy(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jint {
    ok_or_neg(lxmf::router_destroy(router as u64))
}

// ---- Message ----

/// `RetichatBridge.nativeMessageCreate(destHash: ByteArray, srcHash: ByteArray, content: String, title: String, method: Int, identityHandle: Long): Long`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageCreate(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageAddAttachment(
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

/// `RetichatBridge.nativeMessageAddFieldString(handle: Long, key: Int, value: String): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageAddFieldString(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: jint,
    value: JString,
) -> jint {
    let v = jstring_to_string(&mut env, &value);
    ok_or_neg(lxmf::message_add_field_string(handle as u64, key as u8, &v))
}

/// `RetichatBridge.nativeMessageAddFieldBool(handle: Long, key: Int, value: Boolean): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageAddFieldBool(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: jint,
    value: jni::sys::jboolean,
) -> jint {
    ok_or_neg(lxmf::message_add_field_bool(handle as u64, key as u8, value != 0))
}

/// `RetichatBridge.nativeMessageSend(router: Long, msg: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageSend(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
    msg: jlong,
) -> jint {
    ok_or_neg(lxmf::message_send(router as u64, msg as u64))
}

/// `RetichatBridge.nativeMessageGetState(handle: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageGetState(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageGetProgress(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageGetHash(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    ok_or_neg(lxmf::message_destroy(handle as u64))
}

// ---- Propagation ----

/// `RetichatBridge.nativeRouterSetPropagationNode(router: Long, destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterSetPropagationNode(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterRequestMessages(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
    identity: jlong,
) -> jint {
    ok_or_neg(lxmf::router_request_messages(router as u64, identity as u64))
}

/// `RetichatBridge.nativeRouterGetPropagationState(router: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterGetPropagationState(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterGetPropagationProgress(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterCancelPropagation(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeSetDropAnnounces(
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
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeSetKeepaliveInterval(
    _env: JNIEnv,
    _class: JClass,
    secs: jni::sys::jdouble,
) -> jint {
    ok_or_neg(rns::set_keepalive_interval(secs))
}
