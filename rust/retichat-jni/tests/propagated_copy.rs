use lxmf_rust::lx_message::LXMessage;
use reticulum_rust::destination::{Destination, DestinationType};
use reticulum_rust::identity::Identity;

#[test]
fn propagated_copy_preserves_hash_fields_and_attachments() {
    let source = Destination::new_inbound(
        Some(Identity::new(true)),
        DestinationType::Single,
        "lxmf".to_string(),
        vec!["delivery".to_string()],
    )
    .expect("source destination");
    let destination = Destination::new_outbound(
        Some(Identity::new(true)),
        DestinationType::Single,
        "lxmf".to_string(),
        vec!["delivery".to_string()],
    )
    .expect("destination");

    let mut direct = LXMessage::new(
        Some(destination.clone()),
        Some(source.clone()),
        Some(b"group content".to_vec()),
        Some(b"group title".to_vec()),
        None,
        Some(LXMessage::DIRECT),
        Some(destination.hash.clone()),
        Some(source.hash.clone()),
        None,
        false,
    )
    .expect("direct message");
    direct.add_file_attachment("invite.txt", b"attachment payload".to_vec());
    direct.pack(false).expect("pack direct");

    let mut propagated = direct.propagated_copy().expect("propagated copy");
    assert_eq!(propagated.desired_method, Some(LXMessage::PROPAGATED));
    assert_eq!(propagated.timestamp, direct.timestamp);
    assert_eq!(propagated.fields, direct.fields);

    propagated.pack(false).expect("pack propagated");
    assert_eq!(propagated.hash, direct.hash);
}