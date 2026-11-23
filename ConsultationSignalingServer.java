package za.co.nmas.nmas.websocket;

import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket signaling server for WebRTC video consultations.
 * Integrated from VideoConsultations project into NMAS.
 * Endpoint: /consultation/{roomId}/{role}  (role = "patient" | "doctor")
 */
@Component
@ServerEndpoint("/consultation/{roomId}/{role}")
public class ConsultationSignalingServer {

    // Maps roomId -> (role -> Session)
    private static final Map<String, Map<String, Session>> roomRooms = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("roomId") String roomId, @PathParam("role") String role) {
        if (!"patient".equalsIgnoreCase(role) && !"doctor".equalsIgnoreCase(role)) {
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Invalid role context"));
            } catch (Exception e) { /* silent */ }
            return;
        }
        String normalizedRole = role.toLowerCase();
        roomRooms.putIfAbsent(roomId, new ConcurrentHashMap<>());
        Map<String, Session> peers = roomRooms.get(roomId);

        peers.put(normalizedRole, session);
        System.out.println("[NMAS Video] Room [" + roomId + "] connected as [" + normalizedRole + "]");
        notifyPeerStatus(roomId, normalizedRole, true);

        // Also tell the JUST-CONNECTED session whether the peer is already
        // there — without this, whichever side connects first never learns
        // the other side is already waiting once it later joins mid-flight.
        String peerRole = "patient".equals(normalizedRole) ? "doctor" : "patient";
        Session peerSession = peers.get(peerRole);
        boolean peerAlreadyConnected = peerSession != null && peerSession.isOpen();
        String selfPayload = String.format("{\"peerStatus\":\"%s\", \"connected\":%b}", peerRole, peerAlreadyConnected);
        sendSafely(session, selfPayload);
    }

    @OnMessage
    public void onMessage(String message, Session session, @PathParam("roomId") String roomId, @PathParam("role") String role) {
        String normalizedRole = role.toLowerCase();
        String targetRole = "patient".equals(normalizedRole) ? "doctor" : "patient";
        Map<String, Session> roomPeers = roomRooms.get(roomId);
        if (roomPeers != null) {
            Session targetSession = roomPeers.get(targetRole);
            if (targetSession != null && targetSession.isOpen()) {
                sendSafely(targetSession, message);
            }
        }
    }

    @OnClose
    public void onClose(Session session, @PathParam("roomId") String roomId, @PathParam("role") String role) {
        String normalizedRole = role.toLowerCase();
        roomRooms.computeIfPresent(roomId, (id, peers) -> {
            peers.remove(normalizedRole);
            return peers.isEmpty() ? null : peers;
        });
        sessionLocks.remove(session);
        System.out.println("[NMAS Video] Room [" + roomId + "] — [" + normalizedRole + "] disconnected");
        notifyPeerStatus(roomId, normalizedRole, false);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        System.err.println("[NMAS Video] Signaling error: " + throwable.getMessage());
    }

    private void notifyPeerStatus(String roomId, String actingRole, boolean isConnected) {
        String targetRole = "patient".equals(actingRole) ? "doctor" : "patient";
        Map<String, Session> roomPeers = roomRooms.get(roomId);
        if (roomPeers != null) {
            Session targetSession = roomPeers.get(targetRole);
            if (targetSession != null && targetSession.isOpen()) {
                String payload = String.format("{\"peerStatus\":\"%s\", \"connected\":%b}", actingRole, isConnected);
                sendSafely(targetSession, payload);
            }
        }
    }

    /**
     * Called from VideoController the instant either the doctor or the
     * patient clicks "End Call". Pushes an immediate signal to whoever is
     * still in the room, so both sides leave in sync — instead of one party
     * potentially staying on a call whose status has already changed to
     * ENDED in the database without them knowing.
     */
    public static void broadcastCallEnded(String roomId, String endedByRole) {
        Map<String, Session> peers = roomRooms.get(roomId);
        if (peers == null) return;
        String payload = String.format("{\"callEnded\":true,\"endedBy\":\"%s\"}", endedByRole);
        peers.values().forEach(s -> {
            if (s.isOpen()) sendSafelyStatic(s, payload);
        });
    }

    // ------------------------------------------------------------------
    //  Safe, serialized sending
    // ------------------------------------------------------------------
    // Jakarta WebSocket's async remote (getAsyncRemote().sendText(...)) is
    // NOT safe to call again on the same Session while a previous send to
    // that same session hasn't finished — doing so throws exactly
    // "The remote endpoint was in state [TEXT_FULL_WRITING] which is an
    // invalid state for called method" and can knock the connection over.
    // WebRTC signaling fires off many ICE candidates in very quick
    // succession (often 10-20+ within milliseconds), each relayed here as
    // a separate onMessage call, all targeting the SAME peer Session — so
    // this collision was happening essentially every single call attempt,
    // silently dropping candidates and sometimes killing the session
    // entirely. The fix: serialize every send to a given session behind a
    // per-session lock, using the basic (blocking) remote instead of the
    // async one so a send can never overlap with another to the same
    // session. Messages here are tiny (SDP/ICE JSON), so the brief block
    // is negligible.
    private static final Map<Session, Object> sessionLocks = new ConcurrentHashMap<>();

    private void sendSafely(Session session, String payload) {
        sendSafelyStatic(session, payload);
    }

    private static void sendSafelyStatic(Session session, String payload) {
        Object lock = sessionLocks.computeIfAbsent(session, s -> new Object());
        synchronized (lock) {
            try {
                if (session.isOpen()) {
                    session.getBasicRemote().sendText(payload);
                }
            } catch (Exception e) {
                System.err.println("[NMAS Video] Failed to send to session: " + e.getMessage());
            }
        }
    }
}
