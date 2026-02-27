package ist.depchain.network;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import ist.depchain.common.Ack;
import ist.depchain.common.Envelope;
import ist.depchain.network.interfaces.Link;
import ist.depchain.network.interfaces.MessageHandler;
import ist.depchain.network.interfaces.SendHandle;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class AuthenticatedPerfectLink implements Link {

    private final String selfId;
    private final Link stubbornLink;
    private final Link fairLossLink;
    private final Map<Long, SendHandle> pendingMessages = new ConcurrentHashMap<>();
    private final Map<String, Long> nextExpected = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, byte[]>> pendingDeliveries = new ConcurrentHashMap<>();
    private final AtomicLong localSequenceCounter = new AtomicLong(0);

    private final PrivateKey privateKey;
    private final Map<String, PublicKey> trustedKeys;

    private MessageHandler handler;

    public AuthenticatedPerfectLink(String selfId, Link stubbornLink, Link fairLossLink,
                                    PrivateKey privateKey, Map<String, PublicKey> trustedKeys) {
        this.selfId = selfId;
        this.stubbornLink = stubbornLink;
        this.fairLossLink = fairLossLink;
        this.privateKey = privateKey;
        this.trustedKeys = trustedKeys;
        stubbornLink.registerReceiver(this::handleIncomingMessage);
    }

    // --- Crypto ---

    private byte[] sign(byte[] data) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey);
        sig.update(data);
        return sig.sign();
    }

    private boolean verify(String senderId, byte[] data, byte[] signature) {
        try {
            PublicKey pubKey = trustedKeys.get(senderId);
            if (pubKey == null) {
                System.err.println("PerfectLink: No trusted key for " + senderId);
                return false;
            }
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(pubKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    // Builds an envelope WITHOUT signature, serializes it, signs those bytes,
    // then rebuilds the envelope WITH the signature field set.
    // This way the signature covers all fields (senderId, seq, payload/ack).
    private Envelope signEnvelope(Envelope.Builder builder) throws Exception {
        byte[] unsignedBytes = builder.build().toByteArray(); // sign over unsigned bytes
        byte[] signature = sign(unsignedBytes);
        return builder.setSignature(ByteString.copyFrom(signature)).build();
    }

    // Verifies the signature on a received envelope.
    // Re-builds the envelope without the signature field and verifies against those bytes.
    private boolean verifyEnvelope(Envelope envelope) {
        byte[] receivedSig = envelope.getSignature().toByteArray();
        if (receivedSig.length == 0) {
            System.err.println("PerfectLink: Missing signature from " + envelope.getSenderId());
            return false;
        }
        // Strip signature to get the bytes that were originally signed
        Envelope unsigned = envelope.toBuilder().clearSignature().build();
        return verify(envelope.getSenderId(), unsigned.toByteArray(), receivedSig);
    }

    // --- Send ---

    @Override
    public SendHandle send(String destinationId, byte[] payload) {
        long seq = localSequenceCounter.incrementAndGet();

        Envelope.Builder builder = Envelope.newBuilder()
                .setSenderId(selfId)
                .setSequenceNumber(seq)
                .setPayload(ByteString.copyFrom(payload));

        Envelope envelope;
        try {
            envelope = signEnvelope(builder);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign envelope", e);
        }

        SendHandle handle = stubbornLink.send(destinationId, envelope.toByteArray());
        pendingMessages.putIfAbsent(seq, handle);
        return handle;
    }

    // --- Receive ---

    private void handleIncomingMessage(String senderId, byte[] data) {
        try {
            Envelope envelope = Envelope.parseFrom(data);

            // ACKs are signed too — verify before processing
            if (!verifyEnvelope(envelope)) {
                //System.err.println("PerfectLink: AUTHENTICATION FAILED from " + envelope.getSenderId() + " — dropping");
                return;
            }

            if (envelope.hasAck()) {
                handleAck(envelope.getAck());
                return;
            }

            long seq = envelope.getSequenceNumber();
            String envSenderId = envelope.getSenderId(); // use the authenticated sender id from the envelope, not the transport-level senderId
            long nextExpectedSeq = nextExpected.getOrDefault(envSenderId, 1L);

            sendAck(envSenderId, seq);

            if (seq == nextExpectedSeq) {
                //System.out.println("PerfectLink: Delivering message from " + envSenderId + " seq=" + seq);
                nextExpected.put(envSenderId, seq + 1);

                if (handler != null) {
                    handler.onReceive(envSenderId, envelope.getPayload().toByteArray());
                }

                // flush buffered in-order messages
                Map<Long, byte[]> buffered = pendingDeliveries.getOrDefault(envSenderId, new ConcurrentHashMap<>());
                long next = nextExpected.get(envSenderId);
                while (buffered.containsKey(next)) {
                    byte[] bufferedPayload = buffered.remove(next);
                    //System.out.println("PerfectLink: Delivering buffered message from " + envSenderId + " seq=" + next);
                    if (handler != null) handler.onReceive(envSenderId, bufferedPayload);
                    nextExpected.put(envSenderId, ++next);
                }

            } else if (seq < nextExpectedSeq) {
                //System.out.println("PerfectLink: Duplicate/old from " + envSenderId + " seq=" + seq + " (expected " + nextExpectedSeq + ") — ignoring");

            } else { // seq > nextExpectedSeq
                //System.out.println("PerfectLink: Out-of-order from " + envSenderId + " seq=" + seq + " (expected " + nextExpectedSeq + ") — buffering");
                pendingDeliveries.computeIfAbsent(envSenderId, k -> new ConcurrentHashMap<>())
                        .put(seq, envelope.getPayload().toByteArray());
            }

        } catch (InvalidProtocolBufferException e) {
            //System.err.println("PerfectLink: MALFORMED packet from " + senderId + " (likely tampered) — dropping");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleAck(Ack ack) {
        long seq = ack.getOriginalSequenceNumber();
        SendHandle handle = pendingMessages.remove(seq);
        if (handle == null) return;
        handle.cancel();
        //System.out.println("PerfectLink: ACK received for seq=" + seq + ", stopped retransmission");
    }

    private void sendAck(String destinationId, long seq) {
        Ack ack = Ack.newBuilder()
                .setOriginalSender(selfId)         // quem está a fazer ACK
                .setOriginalSequenceNumber(seq)
                .build();

        Envelope.Builder builder = Envelope.newBuilder()
                .setSenderId(selfId)
                .setSequenceNumber(0)
                .setAck(ack);

        try {
            Envelope ackEnvelope = signEnvelope(builder);
            fairLossLink.send(destinationId, ackEnvelope.toByteArray());
        } catch (Exception e) {
            System.err.println("PerfectLink: Failed to sign ACK — " + e.getMessage());
        }
    }

    @Override
    public void registerReceiver(MessageHandler handler) { this.handler = handler; }

    @Override
    public void start() { stubbornLink.start(); }

    @Override
    public void stop() { stubbornLink.stop(); }
}