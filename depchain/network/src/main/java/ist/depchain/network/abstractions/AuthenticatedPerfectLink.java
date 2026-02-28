package ist.depchain.network.abstractions;

import ist.depchain.network.utils.Config;
import ist.depchain.common.Ack;
import ist.depchain.common.Envelope;
import ist.depchain.network.crypto.Crypto;
import ist.depchain.network.interfaces.Link;
import ist.depchain.network.interfaces.MessageHandler;
import ist.depchain.network.interfaces.SendHandle;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class AuthenticatedPerfectLink implements Link {

    private final Config config;
    private final Link stubbornLink;
    private final Link fairLossLink;
    private Map<Long, SendHandle> pendingMessages = new ConcurrentHashMap<>();
    private Map<String, Long> nextExpected = new ConcurrentHashMap<>();
    private Map<String, Map<Long, byte[]>> pendingDeliveries = new ConcurrentHashMap<>();
    private AtomicLong localSequenceCounter = new AtomicLong(0);

    private MessageHandler handler;

    public AuthenticatedPerfectLink(Config config, Link stubbornLink, Link fairLossLink) {
        this.config = config;
        this.stubbornLink = stubbornLink;
        this.fairLossLink = fairLossLink;
        stubbornLink.registerReceiver(this::handleIncomingMessage);
    }
    
    @Override
    public SendHandle send(String destinationId, byte[] payload) {
        long seq = localSequenceCounter.incrementAndGet();
        
        Envelope.Builder builder = Envelope.newBuilder()
        .setSenderId(config.getSelfId())
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
            
            // ACKs are signed too, so verify before processing
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
                
                // after delivering this message, check if we have buffered messages that can now be delivered
                Map<Long, byte[]> buffered = pendingDeliveries.getOrDefault(envSenderId, new ConcurrentHashMap<>());
                long next = nextExpected.get(envSenderId);
                while (buffered.containsKey(next)) {
                    byte[] bufferedPayload = buffered.remove(next);
                    //System.out.println("PerfectLink: Delivering buffered message from " + envSenderId + " seq=" + next);
                    if (handler != null) handler.onReceive(envSenderId, bufferedPayload);
                    nextExpected.put(envSenderId, next + 1);
                }
                
            } else if (seq < nextExpectedSeq) {
                //System.out.println("PerfectLink: Duplicate/old from " + envSenderId + " seq=" + seq + " (expected " + nextExpectedSeq + ") — ignoring");
                
            } else { // seq > nextExpectedSeq
                //System.out.println("PerfectLink: Out-of-order from " + envSenderId + " seq=" + seq + " (expected " + nextExpectedSeq + ") — buffering");
                pendingDeliveries.putIfAbsent(envSenderId, new ConcurrentHashMap<>());
                pendingDeliveries.get(envSenderId).put(seq, envelope.getPayload().toByteArray());
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
        .setOriginalSender(destinationId)         
        .setOriginalSequenceNumber(seq)
        .build();
        
        Envelope.Builder builder = Envelope.newBuilder()
        .setSenderId(config.getSelfId())
        .setSequenceNumber(0)
        .setAck(ack);
        
        try {
            Envelope ackEnvelope = signEnvelope(builder);
            fairLossLink.send(destinationId, ackEnvelope.toByteArray());
        } catch (Exception e) {
            System.err.println("PerfectLink: Failed to sign ACK -> " + e.getMessage());
        }
    }
    
    // builds envelope WITHOUT signature, serialize, signs the bytes, rebuild envelope WITH signature -> signature covers all fields
    private Envelope signEnvelope(Envelope.Builder builder) throws Exception {
        byte[] unsignedBytes = builder.build().toByteArray(); // sign over unsigned bytes
        byte[] signature = Crypto.sign(unsignedBytes, config.getSelfPrivateKeyPathString(), config.getSignatureAlgorithm());
        return builder.setSignature(ByteString.copyFrom(signature)).build();
    }
    
    // re-builds the envelope without the signature field and verifies against those bytes
    private boolean verifyEnvelope(Envelope envelope) {
        byte[] receivedSig = envelope.getSignature().toByteArray();
        if (receivedSig.length == 0) {
            System.err.println("PerfectLink: Missing signature from " + envelope.getSenderId());
            return false;
        }
        // strip signature to get the bytes that were originally signed
        Envelope unsigned = envelope.toBuilder().clearSignature().build();
        return Crypto.verify(unsigned.toByteArray(), receivedSig, config.getTrustedProcessKeyPathString(envelope.getSenderId()), config.getSignatureAlgorithm());
    }

    @Override
    public void registerReceiver(MessageHandler handler) { this.handler = handler; }

    @Override
    public void start() { stubbornLink.start(); }

    @Override
    public void stop() { stubbornLink.stop(); }
}