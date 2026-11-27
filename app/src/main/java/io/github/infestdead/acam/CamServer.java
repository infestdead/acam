package io.github.infestdead.acam;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small RTSP server for camera mode: H.264 video and AAC sound, RTP interleaved on the RTSP
 * connection (the only transport the viewer uses). Plain Java, no Android classes, so it can be
 * exercised on a desktop JVM.
 *
 * Every request except OPTIONS needs digest authentication, so the password itself never crosses
 * the network. The stream is not encrypted: see the README.
 *
 * The viewer also uses the connection for remote control: GET_PARAMETER with "acam" in the body
 * returns the camera's settings and SET_PARAMETER changes them (see {@link Listener#control}).
 */
final class CamServer {
    interface Listener {
        /** The first viewer asked for the stream (true) or the last one left (false). */
        void onWanted(boolean wanted);
        /** How many viewers are being sent the stream. */
        void onViewers(int watching);
        /** A viewer just started: it can show nothing until the next key frame. */
        void onKeyFrameWanted();
        /**
         * A viewer asked about (empty map) or changed the camera's settings, e.g. "lens" or "light".
         * Returns the settings as they now are, one "name: value" per line.
         */
        String control(Map<String, String> changes);
    }

    /** The user name in a camera-mode address; the password is the phone's code. */
    static final String USER = "acam";
    private static final int MAX_CLIENTS = 4;
    private static final int MAX_PAYLOAD = 1400;     // RTP payload bytes per packet
    private static final int QUEUE_PACKETS = 600;    // a viewer this far behind is dropped; it reconnects
    private static final long CONFIG_WAIT_MS = 8000; // for the camera to start and the encoder to describe itself
    private static final int MAX_AUTH_FAILURES = 5;
    private static final String REALM = "ACam";
    private static final Pattern AUTH_FIELD = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))");
    private static final Pattern INTERLEAVED = Pattern.compile("interleaved=(\\d+)-(\\d+)");

    private final int port;
    private final String user, password;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final List<Client> clients = new ArrayList<>(); // guarded by itself
    private int wanting;                                    // guarded by clients
    private final Object configLock = new Object();
    private byte[] sps, pps;                                // guarded by configLock
    private volatile int audioRate;                         // 0: no sound
    private volatile ServerSocket server;

    CamServer(int port, String user, String password, Listener listener) {
        this.port = port;
        this.user = user;
        this.password = password;
        this.listener = listener;
    }

    void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        server = s;
        Thread t = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket socket = s.accept();
                    Client c = new Client(socket);
                    boolean full;
                    synchronized (clients) {
                        full = clients.size() >= MAX_CLIENTS;
                        if (!full) clients.add(c);
                    }
                    if (full) socket.close();
                    else c.start();
                } catch (IOException e) {
                    // closed by stop(), or one failed accept: the loop condition decides
                }
            }
        }, "acam-rtsp-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        ServerSocket s = server;
        server = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
        dropClients();
    }

    /**
     * Disconnect everyone watching, e.g. because the picture changed shape. Viewers reconnect by
     * themselves. Remote-control connections are left alone: one may still be waiting for its reply.
     */
    void dropClients() {
        List<Client> all = new ArrayList<>();
        synchronized (clients) {
            for (Client c : clients) if (c.wants) all.add(c);
        }
        for (Client c : all) c.close();
    }

    /** Sound format, fixed for the server's life. Rate 0 means the stream has no sound. */
    void setAudio(int sampleRate) {
        audioRate = sampleRate;
    }

    /** The encoder's parameter sets, with or without a leading start code. */
    void setVideoConfig(byte[] newSps, byte[] newPps) {
        synchronized (configLock) {
            sps = stripStartCode(newSps);
            pps = stripStartCode(newPps);
            configLock.notifyAll();
        }
    }

    void clearVideoConfig() {
        synchronized (configLock) { sps = pps = null; }
    }

    /** One encoded picture in Annex B form (start codes between NAL units). */
    void video(ByteBuffer frame, long ptsUs, boolean key) {
        List<Client> to = playing();
        if (to.isEmpty()) return;
        byte[] data = new byte[frame.remaining()];
        frame.duplicate().get(data);
        int[] nals = splitNals(data);
        byte[] s, p;
        synchronized (configLock) { s = sps; p = pps; }
        for (Client c : to) c.sendVideo(data, nals, ptsUs, key, s, p);
    }

    /** One raw AAC frame (no ADTS header). */
    void audio(ByteBuffer frame, long ptsUs) {
        List<Client> to = playing();
        if (to.isEmpty()) return;
        byte[] data = new byte[frame.remaining()];
        frame.duplicate().get(data);
        for (Client c : to) c.sendAudio(data, ptsUs);
    }

    private List<Client> playing() {
        List<Client> out = new ArrayList<>(MAX_CLIENTS);
        synchronized (clients) {
            for (Client c : clients) if (c.playing) out.add(c);
        }
        return out;
    }

    private void reportViewers() {
        listener.onViewers(playing().size());
    }

    private byte[][] awaitConfig() {
        long until = System.currentTimeMillis() + CONFIG_WAIT_MS;
        synchronized (configLock) {
            while (sps == null || pps == null) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) return null;
                try {
                    configLock.wait(left);
                } catch (InterruptedException e) {
                    return null;
                }
            }
            return new byte[][]{sps, pps};
        }
    }

    private final class Client extends Thread {
        private final Socket socket;
        private final String nonce = hex(randomBytes(16));
        private final String session = hex(randomBytes(8));
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_PACKETS);
        private OutputStream out;
        private Thread writer;
        volatile boolean wants;              // asked for the stream; written under clients
        private boolean closed;              // guarded by clients
        private int authFailures;
        private int videoChannel = -1, audioChannel = -1;
        private String videoUrl, audioUrl;
        volatile boolean playing;
        // Sending state, touched only by the threads delivering frames (one for video, one for sound).
        private final Object sendLock = new Object();
        private long t0 = Long.MIN_VALUE;    // capture time that maps to RTP time 0, set by the first frame sent
        private int videoSeq = 1, audioSeq = 1;
        private long audioTs = -1;
        private final int videoSsrc = random.nextInt(), audioSsrc = random.nextInt();

        Client(Socket socket) {
            super("acam-rtsp-client");
            setDaemon(true);
            this.socket = socket;
        }

        @Override public void run() {
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(15_000); // until PLAY: an idle connection must not hold a slot
                InputStream in = new BufferedInputStream(socket.getInputStream());
                out = socket.getOutputStream();
                for (Request r; (r = Request.read(in)) != null; ) {
                    if (!handle(r)) break;
                }
            } catch (IOException | RuntimeException e) {
                // gone, or talking nonsense: either way the connection ends
            } finally {
                close();
            }
        }

        /** Returns false to end the connection. */
        private boolean handle(Request r) throws IOException {
            String cseq = r.header("cseq", "0");
            if (r.method.equals("OPTIONS")) {
                reply(200, cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER\r\n", null);
                return true;
            }
            if (!authorised(r)) {
                if (r.header("authorization", null) != null) {
                    authFailures++;
                    try {
                        Thread.sleep(400L * authFailures); // makes guessing the code hopeless
                    } catch (InterruptedException e) {
                        return false;
                    }
                }
                reply(401, cseq, "WWW-Authenticate: Digest realm=\"" + REALM + "\", nonce=\"" + nonce + "\"\r\n", null);
                return authFailures < MAX_AUTH_FAILURES;
            }
            switch (r.method) {
                case "DESCRIBE": {
                    want();
                    byte[][] config = awaitConfig();
                    if (config == null) {
                        reply(503, cseq, "", null);
                        return false;
                    }
                    String base = r.uri.endsWith("/") ? r.uri : r.uri + "/";
                    reply(200, cseq, "Content-Base: " + base + "\r\nContent-Type: application/sdp\r\n",
                            sdp(config[0], config[1]));
                    return true;
                }
                case "SETUP": {
                    String transport = r.header("transport", "");
                    Matcher m = INTERLEAVED.matcher(transport);
                    if (!transport.contains("RTP/AVP/TCP") || !m.find()) {
                        reply(461, cseq, "", null); // only RTP over this connection; players then retry that way
                        return true;
                    }
                    int channel = Integer.parseInt(m.group(1));
                    if (channel < 0 || channel > 254) {
                        reply(400, cseq, "", null);
                        return true;
                    }
                    if (r.uri.contains("trackID=1")) {
                        if (audioRate == 0) {
                            reply(404, cseq, "", null);
                            return true;
                        }
                        audioChannel = channel;
                        audioUrl = r.uri;
                    } else if (r.uri.contains("trackID=0")) {
                        videoChannel = channel;
                        videoUrl = r.uri;
                    } else {
                        reply(404, cseq, "", null);
                        return true;
                    }
                    want();
                    reply(200, cseq, "Transport: RTP/AVP/TCP;unicast;interleaved=" + channel + "-" + (channel + 1)
                            + "\r\nSession: " + session + ";timeout=60\r\n", null);
                    return true;
                }
                case "PLAY": {
                    if (videoChannel < 0 && audioChannel < 0) {
                        reply(455, cseq, "", null);
                        return true;
                    }
                    StringBuilder info = new StringBuilder();
                    if (videoChannel >= 0) info.append("url=").append(videoUrl).append(";seq=1;rtptime=0");
                    if (audioChannel >= 0) {
                        if (info.length() > 0) info.append(',');
                        info.append("url=").append(audioUrl).append(";seq=1;rtptime=0");
                    }
                    reply(200, cseq, "Session: " + session + "\r\nRange: npt=0.000-\r\nRTP-Info: " + info + "\r\n", null);
                    if (!playing) {
                        socket.setSoTimeout(0); // from here a dead viewer shows as a queue that stops draining
                        writer = new Thread(this::write, "acam-rtsp-send");
                        writer.setDaemon(true);
                        writer.start();
                        playing = true;
                        listener.onKeyFrameWanted();
                        reportViewers();
                    }
                    return true;
                }
                case "TEARDOWN":
                    reply(200, cseq, "", null);
                    return false;
                case "GET_PARAMETER":
                    if (r.body.trim().equals("acam")) {
                        reply(200, cseq, "Content-Type: text/parameters\r\n", listener.control(new HashMap<>()));
                    } else {
                        reply(200, cseq, "Session: " + session + "\r\n", null); // a player keeping the session alive
                    }
                    return true;
                case "SET_PARAMETER": {
                    Map<String, String> changes = new HashMap<>();
                    for (String line : r.body.split("\r?\n")) {
                        int colon = line.indexOf(':');
                        if (colon > 0) changes.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                    }
                    reply(200, cseq, "Content-Type: text/parameters\r\n", listener.control(changes));
                    return true;
                }
                case "PAUSE":
                    reply(200, cseq, "Session: " + session + "\r\n", null);
                    return true;
                default:
                    reply(501, cseq, "", null);
                    return true;
            }
        }

        private boolean authorised(Request r) {
            String header = r.header("authorization", null);
            if (header == null || !header.regionMatches(true, 0, "Digest ", 0, 7)) return false;
            Map<String, String> f = new HashMap<>();
            Matcher m = AUTH_FIELD.matcher(header.substring(7));
            while (m.find()) f.put(m.group(1).toLowerCase(Locale.US), m.group(2) != null ? m.group(2) : m.group(3));
            String uri = f.get("uri"), response = f.get("response");
            if (uri == null || response == null || !user.equals(f.get("username")) || !nonce.equals(f.get("nonce"))) {
                return false;
            }
            String expected = md5(md5(user + ":" + REALM + ":" + password) + ":" + nonce + ":" + md5(r.method + ":" + uri));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    response.toLowerCase(Locale.US).getBytes(StandardCharsets.US_ASCII));
        }

        private String sdp(byte[] s, byte[] p) {
            StringBuilder b = new StringBuilder();
            b.append("v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=ACam\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\na=control:*\r\n");
            b.append("m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n");
            b.append("a=fmtp:96 packetization-mode=1;profile-level-id=")
                    .append(s.length >= 4 ? hex(new byte[]{s[1], s[2], s[3]}) : "42e01f")
                    .append(";sprop-parameter-sets=").append(base64(s)).append(',').append(base64(p)).append("\r\n");
            b.append("a=control:trackID=0\r\n");
            int rate = audioRate;
            if (rate != 0) {
                // AudioSpecificConfig: AAC-LC, the rate's index, one channel.
                int config = (2 << 11) | (rateIndex(rate) << 7) | (1 << 3);
                b.append("m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/").append(rate).append("/1\r\n");
                // Media3 reads profile-level-id as the AAC object type ("mp4a.40.<id>"), so 2, for AAC-LC,
                // is the value it can play. Other players go by the config.
                b.append("a=fmtp:97 streamtype=5;profile-level-id=2;mode=AAC-hbr;sizelength=13;indexlength=3;"
                        + "indexdeltalength=3;config=").append(String.format(Locale.US, "%04x", config)).append("\r\n");
                b.append("a=control:trackID=1\r\n");
            }
            return b.toString();
        }

        private void reply(int code, String cseq, String headers, String body) throws IOException {
            byte[] content = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String head = "RTSP/1.0 " + code + " " + reason(code) + "\r\nCSeq: " + cseq + "\r\nServer: ACam\r\n" + headers
                    + (body == null ? "" : "Content-Length: " + content.length + "\r\n") + "\r\n";
            synchronized (this) {
                out.write(head.getBytes(StandardCharsets.UTF_8));
                out.write(content);
                out.flush();
            }
        }

        private void write() {
            try {
                while (true) {
                    byte[] packet = queue.take();
                    synchronized (this) { out.write(packet); }
                }
            } catch (IOException | InterruptedException e) {
                close();
            }
        }

        void sendVideo(byte[] data, int[] nals, long ptsUs, boolean key, byte[] s, byte[] p) {
            if (videoChannel < 0) return;
            synchronized (sendLock) {
                if (t0 == Long.MIN_VALUE) {
                    if (!key) return; // nothing can be decoded before a key frame
                    t0 = ptsUs;
                }
                int ts = (int) ((ptsUs - t0) * 9 / 100);
                if (key && s != null && p != null) {
                    // In band as well as in the description, for players that join without reading it.
                    nal(s, 0, s.length, ts, false);
                    nal(p, 0, p.length, ts, false);
                }
                int last = -1;
                for (int i = 0; i < nals.length; i += 2) {
                    if (!skipped(data[nals[i]], key)) last = i;
                }
                for (int i = 0; i < nals.length; i += 2) {
                    if (!skipped(data[nals[i]], key)) nal(data, nals[i], nals[i + 1], ts, i == last);
                }
            }
        }

        /** Parameter sets inside a key frame were just sent; no need for them twice. */
        private boolean skipped(byte nalHeader, boolean key) {
            int type = nalHeader & 0x1F;
            return key && (type == 7 || type == 8);
        }

        private void nal(byte[] data, int from, int to, int ts, boolean endOfPicture) {
            int length = to - from;
            if (length <= 0) return;
            if (length <= MAX_PAYLOAD) {
                byte[] packet = rtp(videoChannel, 96, endOfPicture, videoSeq++, ts, videoSsrc, length);
                System.arraycopy(data, from, packet, 16, length);
                enqueue(packet);
                return;
            }
            // Too big for one packet: FU-A fragments, each led by two bytes that stand in for the NAL header.
            int header = data[from] & 0xFF;
            for (int at = from + 1; at < to; ) {
                int n = Math.min(MAX_PAYLOAD - 2, to - at);
                boolean first = at == from + 1, lastPart = at + n == to;
                byte[] packet = rtp(videoChannel, 96, endOfPicture && lastPart, videoSeq++, ts, videoSsrc, n + 2);
                packet[16] = (byte) ((header & 0xE0) | 28);
                packet[17] = (byte) ((first ? 0x80 : 0) | (lastPart ? 0x40 : 0) | (header & 0x1F));
                System.arraycopy(data, at, packet, 18, n);
                enqueue(packet);
                at += n;
            }
        }

        void sendAudio(byte[] frame, long ptsUs) {
            if (audioChannel < 0 || frame.length == 0 || frame.length > 8191) return;
            int rate = audioRate;
            synchronized (sendLock) {
                if (t0 == Long.MIN_VALUE) {
                    if (videoChannel >= 0) return; // the picture's first key frame sets the clock
                    t0 = ptsUs;
                }
                if (ptsUs < t0) return;
                long exact = (ptsUs - t0) * rate / 1_000_000;
                // Frames follow each other 1024 samples apart; only a real gap may break that step.
                if (audioTs < 0 || Math.abs(exact - (audioTs + 1024)) > rate / 20) audioTs = exact;
                else audioTs += 1024;
                byte[] packet = rtp(audioChannel, 97, true, audioSeq++, (int) audioTs, audioSsrc, frame.length + 4);
                packet[16] = 0;
                packet[17] = 16;                                // one 16-bit AU header follows
                packet[18] = (byte) (frame.length >> 5);        // 13 bits of size, 3 of index
                packet[19] = (byte) (frame.length << 3);
                System.arraycopy(frame, 0, packet, 20, frame.length);
                enqueue(packet);
            }
        }

        /** An interleaved frame ("$", channel, length) holding an RTP header and room for the payload. */
        private byte[] rtp(int channel, int type, boolean marker, int seq, int ts, int ssrc, int payload) {
            byte[] b = new byte[4 + 12 + payload];
            int length = 12 + payload;
            b[0] = '$';
            b[1] = (byte) channel;
            b[2] = (byte) (length >> 8);
            b[3] = (byte) length;
            b[4] = (byte) 0x80;
            b[5] = (byte) ((marker ? 0x80 : 0) | type);
            b[6] = (byte) (seq >> 8);
            b[7] = (byte) seq;
            b[8] = (byte) (ts >> 24);
            b[9] = (byte) (ts >> 16);
            b[10] = (byte) (ts >> 8);
            b[11] = (byte) ts;
            b[12] = (byte) (ssrc >> 24);
            b[13] = (byte) (ssrc >> 16);
            b[14] = (byte) (ssrc >> 8);
            b[15] = (byte) ssrc;
            return b;
        }

        private void enqueue(byte[] packet) {
            if (!queue.offer(packet)) close(); // hopelessly behind: start afresh rather than show old pictures
        }

        private void want() {
            boolean first;
            synchronized (clients) {
                if (wants || closed) return;
                wants = true;
                first = ++wanting == 1;
            }
            if (first) listener.onWanted(true);
        }

        void close() {
            boolean last, wasPlaying;
            synchronized (clients) {
                if (closed) return;
                closed = true;
                clients.remove(this);
                last = wants && --wanting == 0;
                wasPlaying = playing;
                playing = false;
            }
            try { socket.close(); } catch (IOException ignored) { }
            if (writer != null) writer.interrupt();
            if (wasPlaying) reportViewers();
            if (last) listener.onWanted(false);
        }
    }

    private static final class Request {
        String method, uri, body = "";
        final Map<String, String> headers = new HashMap<>();

        String header(String name, String fallback) {
            String v = headers.get(name);
            return v == null ? fallback : v;
        }

        /** The next request, skipping any interleaved data the player sends; null at end of stream. */
        static Request read(InputStream in) throws IOException {
            while (true) {
                int b = in.read();
                if (b < 0) return null;
                if (b == '$') { // RTCP from the player: not needed
                    skip(in, 1);
                    int hi = in.read(), lo = in.read();
                    if (lo < 0) return null;
                    skip(in, (hi << 8) | lo);
                    continue;
                }
                if (b == '\r' || b == '\n') continue;
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                text.write(b);
                int run = 0; // length of the "\r\n\r\n" matched so far
                while (run < 4) {
                    b = in.read();
                    if (b < 0 || text.size() > 8192) return null;
                    text.write(b);
                    run = b == (run % 2 == 0 ? '\r' : '\n') ? run + 1 : (b == '\r' ? 1 : 0);
                }
                String[] lines = new String(text.toByteArray(), StandardCharsets.UTF_8).split("\r\n");
                String[] start = lines[0].split(" ");
                if (start.length < 3) return null;
                Request r = new Request();
                r.method = start[0];
                r.uri = start[1];
                for (int i = 1; i < lines.length; i++) {
                    int colon = lines[i].indexOf(':');
                    if (colon > 0) {
                        r.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                                lines[i].substring(colon + 1).trim());
                    }
                }
                int length = Integer.parseInt(r.header("content-length", "0"));
                if (length < 0 || length > 65536) return null;
                byte[] body = new byte[length];
                for (int i = 0; i < length; i++) {
                    int c = in.read();
                    if (c < 0) throw new IOException("ended inside a message");
                    body[i] = (byte) c;
                }
                r.body = new String(body, StandardCharsets.UTF_8);
                return r;
            }
        }

        private static void skip(InputStream in, int n) throws IOException {
            for (int i = 0; i < n; i++) if (in.read() < 0) throw new IOException("ended inside a message");
        }
    }

    /** Start and end offsets of each NAL unit in an Annex B buffer, start codes excluded. */
    static int[] splitNals(byte[] d) {
        int[] found = new int[16];
        int n = 0, start = -1;
        for (int i = 0; i + 2 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                if (start >= 0) {
                    int end = i > 0 && d[i - 1] == 0 ? i - 1 : i; // a four-byte start code
                    if (n + 2 > found.length) found = java.util.Arrays.copyOf(found, found.length * 2);
                    if (end > start) {
                        found[n++] = start;
                        found[n++] = end;
                    }
                }
                start = i + 3;
                i += 2;
            }
        }
        if (start < 0) return d.length == 0 ? new int[0] : new int[]{0, d.length}; // a bare NAL unit
        if (n + 2 > found.length) found = java.util.Arrays.copyOf(found, found.length + 2);
        if (d.length > start) {
            found[n++] = start;
            found[n++] = d.length;
        }
        return java.util.Arrays.copyOf(found, n);
    }

    private static byte[] stripStartCode(byte[] b) {
        int[] nals = splitNals(b);
        return nals.length < 2 ? b : java.util.Arrays.copyOfRange(b, nals[0], nals[1]);
    }

    private static int rateIndex(int rate) {
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000};
        for (int i = 0; i < rates.length; i++) if (rates[i] == rate) return i;
        throw new IllegalArgumentException("not an AAC sample rate: " + rate);
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 455: return "Method Not Valid in This State";
            case 461: return "Unsupported Transport";
            case 501: return "Not Implemented";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static String md5(String s) {
        try {
            return hex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return s.toString();
    }

    private static String base64(byte[] b) {
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder s = new StringBuilder((b.length + 2) / 3 * 4);
        for (int i = 0; i < b.length; i += 3) {
            int left = b.length - i;
            int v = ((b[i] & 0xFF) << 16) | (left > 1 ? (b[i + 1] & 0xFF) << 8 : 0) | (left > 2 ? b[i + 2] & 0xFF : 0);
            s.append(abc.charAt(v >> 18)).append(abc.charAt((v >> 12) & 63))
                    .append(left > 1 ? abc.charAt((v >> 6) & 63) : '=').append(left > 2 ? abc.charAt(v & 63) : '=');
        }
        return s.toString();
    }
}
