import java.io.IOException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgeofChanEVM (single-file)
 *
 * Purpose:
 * - Build ABI-calldata for DopeModa (register, fund, train, claim, raid commit/reveal, withdraw)
 * - Perform eth_call for read-only views
 * - Perform eth_sendTransaction for state changes (requires an unlocked account on the RPC node)
 * - Perform contract deployment using bytecode from a local Hardhat artifact JSON
 *
 * This file is intentionally dependency-free:
 * - It includes a pure-Java keccak256 implementation (for function selectors)
 * - It includes a minimal ABI encoder for the specific function argument types used by DopeModa
 *
 * Notes:
 * - For live deployments, your RPC must allow eth_sendTransaction without signing (unlocked account),
 *   OR you provide a deployment sender that the RPC considers authorized.
 */
public final class AgeofChanEVM {

    // -----------------------------
    // CLI entry
    // -----------------------------

    public static void main(String[] args) {
        try {
            if (args == null || args.length == 0) {
                usage();
                return;
            }

            String cmd = args[0].trim();
            AgeofChanEVM tool = new AgeofChanEVM();

            switch (cmd) {
                case "deploy" -> tool.runDeploy(args);
                case "view" -> tool.runView(args);
                case "call" -> tool.runCall(args);
                case "build" -> tool.runBuild(args);
                default -> usage();
            }
        } catch (Exception e) {
            System.err.println("AgeofChanEVM error: " + e.getMessage());
            // Keep stack traces short; this is an offline-helper style tool.
        }
    }

    private static void usage() {
        System.out.println("AgeofChanEVM commands:");
        System.out.println("  deploy --rpc <url> --artifact <DopeModa.json> --from <0xaddr> [--gas <wei>] [--value <wei>]");
        System.out.println("  view   --rpc <url> --contract <0xaddr> --fn <name> [--args ...]");
        System.out.println("  call   --rpc <url> --contract <0xaddr> --from <0xaddr> --fn <name> [--args ...] [--gas <wei>] [--value <wei>]");
        System.out.println("  build  --fn <name> [--args ...]   (prints calldata, no RPC call)");
        System.out.println();
        System.out.println("DopeModa function names supported:");
        System.out.println("  registerGang(handle, emblemHash)");
        System.out.println("  fundStash(gangId) [payable]");
        System.out.println("  setSlogan(gangId, slogan)");
        System.out.println("  train(gangId, trainingLine, spentWei)");
        System.out.println("  claimZone(gangId, zoneId, emblemHash) [payable]");
        System.out.println("  commitRaid(fromGangId, fromZone, toZone, tactic, sealed, potWei) [payable]");
        System.out.println("  revealRaid(raidId, revealSalt)");
        System.out.println("  withdrawGang(gangId)");
        System.out.println("  zoneView(zoneId)");
        System.out.println("  raidView(raidId)");
        System.out.println("  getGang(gangId)");
        System.out.println();
        System.out.println("For build/call: args are provided positionally after --fn.");
        System.out.println("Examples:");
        System.out.println("  java AgeofChanEVM build --fn zoneView --args 12");
        System.out.println("  java AgeofChanEVM call --rpc <url> --contract <addr> --from <addr> --fn withdrawGang --args 1");
    }

    // -----------------------------
    // Runtime + configuration
    // -----------------------------

    private static final String DEFAULT_RPC = "https://eth.llamarpc.com";
    private String rpcUrl = DEFAULT_RPC;

    private final Random rnd = new Random();

    private AgeofChanEVM() {}

    // -----------------------------
    // Commands
    // -----------------------------

    private void runDeploy(String[] args) throws Exception {
        String rpc = getArg(args, "--rpc", true);
        String artifact = getArg(args, "--artifact", true);
        String from = getArg(args, "--from", true);
        String artifactJson = Files.readString(Path.of(artifact), StandardCharsets.UTF_8);

        // Minimal artifact parsing:
        // Look for "bytecode":"0x..."
        String bytecode = extractFirstMatch(artifactJson, "\"bytecode\"\\s*:\\s*\"(0x[0-9a-fA-F]*)\"");
        if (bytecode == null) throw new IllegalArgumentException("artifact bytecode not found");

        rpcUrl = rpc;
        System.out.println("Deploying bytecode length=" + (bytecode.length() - 2) / 2 + " bytes ...");

        String data = bytecode;

        Map<String, Object> tx = new HashMap<>();
        tx.put("from", normalizeAddress(from));
        tx.put("data", data);
        // gas/value optional

        String gasWei = getOptionalArg(args, "--gas");
        if (gasWei != null) tx.put("gas", "0x" + new BigInteger(gasWei).toString(16));
        String valueWei = getOptionalArg(args, "--value");
        if (valueWei != null) tx.put("value", "0x" + new BigInteger(valueWei).toString(16));

        String txHash = ethSendTransaction(tx);
        System.out.println("Deploy tx hash: " + txHash);
    }

    private void runView(String[] args) throws Exception {
        String rpc = getArg(args, "--rpc", true);
        String contract = getArg(args, "--contract", true);
        String fn = getArg(args, "--fn", true);
        this.rpcUrl = rpc;

        List<String> fnArgs = getPositionalAfterFn(args);
        String data = buildCalldata(fn, fnArgs);

        String result = ethCall(contract, data);
        System.out.println("eth_call result: " + result);
    }

    private void runCall(String[] args) throws Exception {
        String rpc = getArg(args, "--rpc", true);
        String contract = getArg(args, "--contract", true);
        String from = getArg(args, "--from", true);
        String fn = getArg(args, "--fn", true);
        this.rpcUrl = rpc;

        List<String> fnArgs = getPositionalAfterFn(args);
        String data = buildCalldata(fn, fnArgs);

        Map<String, Object> tx = new HashMap<>();
        tx.put("from", normalizeAddress(from));
        tx.put("to", normalizeAddress(contract));
        tx.put("data", data);

        // Allow a manual tx value override for payable methods
        String gasWei = getOptionalArg(args, "--gas");
        if (gasWei != null) tx.put("gas", "0x" + new BigInteger(gasWei).toString(16));

        String valueWei = getOptionalArg(args, "--value");
        if (valueWei != null) tx.put("value", "0x" + new BigInteger(valueWei).toString(16));

        String txHash = ethSendTransaction(tx);
        System.out.println("call tx hash: " + txHash);
    }

    private void runBuild(String[] args) throws Exception {
        String fn = getArg(args, "--fn", true);
        List<String> fnArgs = getPositionalAfterFn(args);
        String data = buildCalldata(fn, fnArgs);
        System.out.println(data);
    }

    // -----------------------------
    // JSON-RPC minimal client
    // -----------------------------

    private String ethCall(String to, String data) throws IOException {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"eth_call\",\"params\":[{\"to\":\"" + to + "\",\"data\":\"" + data + "\"},\"latest\"],\"id\":1}";
        String resp = postJson(rpcUrl, body);
        return extractJsonString(resp, "result");
    }

    private String ethSendTransaction(Map<String, Object> tx) throws IOException {
        // Build params object manually (this tool aims to be dependency-free).
        StringBuilder obj = new StringBuilder();
        obj.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : tx.entrySet()) {
            if (!first) obj.append(",");
            first = false;
            obj.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof String) {
                obj.append("\"").append(v).append("\"");
            } else {
                obj.append(v.toString());
            }
        }
        obj.append("}");

        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"eth_sendTransaction\",\"params\":[" + obj + "],\"id\":1}";
        String resp = postJson(rpcUrl, body);
        String result = extractJsonString(resp, "result");
        if (result == null || result.isEmpty()) {
            throw new IOException("RPC sendTransaction failed: " + resp);
        }
        return result;
    }

    private String postJson(String urlString, String jsonBody) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));

        int code = conn.getResponseCode();
        String resp = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (code != 200) {
            throw new IOException("HTTP " + code + ": " + resp);
        }
        return resp;
    }

    private static String extractJsonString(String jsonResponse, String key) {
        if (jsonResponse == null) return null;
        // Very simple parser; this is for tool use only.
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(jsonResponse);
        if (!m.find()) return null;
        return m.group(1);
    }

    // -----------------------------
    // ABI encoding (minimal)
    // -----------------------------

    private String buildCalldata(String fnName, List<String> fnArgs) {
        String signature = resolveSignature(fnName);
        String selector = selectorHex(signature);
        AbiType[] types = resolveArgTypes(fnName);
        if (fnArgs.size() != types.length) {
            throw new IllegalArgumentException("Wrong arg count for " + fnName + ": expected " + types.length + " got " + fnArgs.size());
        }
        byte[] encoded = Abi.encode(types, fnArgs);
        return "0x" + selector.substring(2) + Hex.toHex(encoded);
    }

    private String resolveSignature(String fnName) {
        return switch (fnName) {
            case "registerGang" -> "registerGang(string,bytes32)";
            case "fundStash" -> "fundStash(uint64)";
            case "setSlogan" -> "setSlogan(uint64,string)";
            case "train" -> "train(uint64,uint8,uint256)";
            case "claimZone" -> "claimZone(uint64,uint16,bytes32)";
            case "commitRaid" -> "commitRaid(uint64,uint16,uint16,uint8,bytes32,uint256)";
            case "revealRaid" -> "revealRaid(uint256,bytes32)";
            case "withdrawGang" -> "withdrawGang(uint64)";
            case "zoneView" -> "zoneView(uint16)";
            case "raidView" -> "raidView(uint256)";
            case "getGang" -> "getGang(uint64)";
            default -> throw new IllegalArgumentException("Unknown function: " + fnName);
        };
    }

    private AbiType[] resolveArgTypes(String fnName) {
        return switch (fnName) {
            case "registerGang" -> new AbiType[] { AbiType.STRING, AbiType.BYTES32 };
            case "fundStash" -> new AbiType[] { AbiType.UINT64 };
            case "setSlogan" -> new AbiType[] { AbiType.UINT64, AbiType.STRING };
            case "train" -> new AbiType[] { AbiType.UINT64, AbiType.UINT8, AbiType.UINT256 };
            case "claimZone" -> new AbiType[] { AbiType.UINT64, AbiType.UINT16, AbiType.BYTES32 };
            case "commitRaid" -> new AbiType[] { AbiType.UINT64, AbiType.UINT16, AbiType.UINT16, AbiType.UINT8, AbiType.BYTES32, AbiType.UINT256 };
            case "revealRaid" -> new AbiType[] { AbiType.UINT256, AbiType.BYTES32 };
            case "withdrawGang" -> new AbiType[] { AbiType.UINT64 };
            case "zoneView" -> new AbiType[] { AbiType.UINT16 };
            case "raidView" -> new AbiType[] { AbiType.UINT256 };
            case "getGang" -> new AbiType[] { AbiType.UINT64 };
            default -> throw new IllegalArgumentException("Unknown function: " + fnName);
        };
    }

    private static String selectorHex(String signature) {
        // selector = first 4 bytes of keccak256(signature)
        byte[] hash = Keccak.keccak256(signature.getBytes(StandardCharsets.US_ASCII));
        String h = Hex.toHex(hash);
        // 8 hex chars = 4 bytes
        return "0x" + h.substring(0, 8);
    }

    private static String normalizeAddress(String addr) {
        if (addr == null) throw new IllegalArgumentException("address missing");
        addr = addr.trim();
        if (addr.startsWith("0x") || addr.startsWith("0X")) addr = addr.substring(2);
        if (addr.length() != 40) throw new IllegalArgumentException("address must be 40 hex chars");
        return "0x" + addr;
    }

    // -----------------------------
    // Arg parsing helpers
    // -----------------------------

    private static List<String> getPositionalAfterFn(String[] args) {
        List<String> out = new ArrayList<>();
        int i = 0;
        // find --fn index
        for (int k = 0; k < args.length; k++) {
            if ("--fn".equals(args[k])) {
                i = k + 2; // skip fn value; next item is first positional
                break;
            }
        }
        // Accept optional --args marker
        int start = -1;
        for (int k = 0; k < args.length; k++) {
            if ("--args".equals(args[k])) { start = k + 1; break; }
        }
        if (start >= 0) {
            for (int k = start; k < args.length; k++) out.add(args[k]);
            return out;
        }

        // Otherwise: treat everything after the fn value as args.
        for (int k = i; k < args.length; k++) out.add(args[k]);
        return out;
    }

    private static String getArg(String[] args, String key, boolean required) {
        if (args == null) throw new IllegalArgumentException("args missing");
        for (int i = 0; i < args.length; i++) {
            if (key.equals(args[i]) && i + 1 < args.length) return args[i + 1];
        }
        if (required) throw new IllegalArgumentException("Missing argument " + key);
        return null;
    }

    private static String getOptionalArg(String[] args, String key) {
        return getArg(args, key, false);
    }

    private static String extractFirstMatch(String text, String regex) {
        Pattern p = Pattern.compile(regex, Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        return m.group(1);
    }

    // -----------------------------
    // Minimal ABI encoder
    // -----------------------------

    enum AbiType {
        UINT8(1),
        UINT16(2),
        UINT64(8),
        UINT256(32),
        BYTES32(32),
        STRING(-1);

        final int size;
        AbiType(int size) { this.size = size; }
    }

    static final class Abi {
        private Abi() {}

        static byte[] encode(AbiType[] types, List<String> args) {
            // Solidity ABI encoding:
            // - For dynamic types (string), we put offset pointers in the head and
            //   append their data in the tail.
            // - For fixed types, we encode directly into the head.
            int headWords = 0;
            for (AbiType t : types) headWords += 1;

            List<byte[]> headParts = new ArrayList<>();
            List<byte[]> tailParts = new ArrayList<>();

            int dynamicCount = 0;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == AbiType.STRING) dynamicCount++;
            }

            int headSizeBytes = headWords * 32;
            int tailCursor = 0;

            for (int i = 0; i < types.length; i++) {
                AbiType t = types[i];
                String a = args.get(i);
                if (t == AbiType.STRING) {
                    // offset = headSize + tailCursor
                    byte[] offsetWord = word((long) (headSizeBytes + tailCursor));
                    headParts.add(offsetWord);

                    byte[] strBytes = aToBytesUtf8(a);
                    byte[] lenWord = word((long) strBytes.length);
                    byte[] padded = rightPadToMultiple(strBytes, 32);
                    byte[] tail = concat(lenWord, padded);
                    tailParts.add(tail);
                    tailCursor += tail.length;
                } else if (t == AbiType.BYTES32) {
                    headParts.add(bytes32Word(a));
                } else if (t == AbiType.UINT8) {
                    headParts.add(uintWord(parseUint(a)));
                } else if (t == AbiType.UINT16) {
                    headParts.add(uintWord(parseUint(a)));
                } else if (t == AbiType.UINT64) {
                    headParts.add(uintWord(parseUint(a)));
                } else if (t == AbiType.UINT256) {
                    headParts.add(uintWord(parseUint(a)));
                } else {
                    throw new IllegalArgumentException("Unsupported type: " + t);
                }
            }

            byte[] head = concatAll(headParts);
            byte[] tail = concatAll(tailParts);
            return concat(head, tail);
        }

        private static byte[] aToBytesUtf8(String a) {
            // For CLI, strings are passed as raw words. We don't try to unescape quotes.
            if (a == null) return new byte[0];
            return a.getBytes(StandardCharsets.UTF_8);
        }

        private static long parseUint(String s) {
            if (s == null || s.isBlank()) return 0;
            s = s.trim();
            if (s.startsWith("0x") || s.startsWith("0X")) return new BigInteger(s.substring(2), 16).longValue();
            return new BigInteger(s).longValue();
        }

        private static byte[] uintWord(long v) {
            // Encode as uint256 word: 32 bytes big-endian.
            return word(BigInteger.valueOf(v).toByteArray(), true);
        }

        private static byte[] word(long v) {
            return word(BigInteger.valueOf(v).toByteArray(), true);
        }

        private static byte[] word(byte[] raw, boolean bigEndian) {
            // Convert to exactly 32 bytes big-endian.
            byte[] out = new byte[32];
            byte[] src = raw;
            // Strip leading sign byte if present
            if (src.length > 32) {
                int start = src.length - 32;
                System.arraycopy(src, start, out, 0, 32);
                return out;
            }
            int start = 32 - src.length;
            System.arraycopy(src, 0, out, start, src.length);
            return out;
        }

        private static byte[] bytes32Word(String a) {
            if (a == null) a = "0x0";
            a = a.trim();
            if (a.startsWith("0x") || a.startsWith("0X")) a = a.substring(2);
            if (a.length() > 64) a = a.substring(a.length() - 64);
            if (a.length() % 2 == 1) a = "0" + a;
            byte[] b = Hex.fromHex(a);
            if (b.length != 32) {
                // left-pad
                byte[] out = new byte[32];
                int start = 32 - b.length;
                System.arraycopy(b, 0, out, start, b.length);
                return out;
            }
            return b;
        }

        private static byte[] rightPadToMultiple(byte[] b, int multiple) {
            int rem = b.length % multiple;
            int pad = rem == 0 ? 0 : (multiple - rem);
            if (pad == 0) return b;
            byte[] out = new byte[b.length + pad];
            System.arraycopy(b, 0, out, 0, b.length);
            return out;
        }

        private static byte[] concatAll(List<byte[]> parts) {
            int total = 0;
            for (byte[] p : parts) if (p != null) total += p.length;
            byte[] out = new byte[total];
            int pos = 0;
            for (byte[] p : parts) {
                if (p == null) continue;
                System.arraycopy(p, 0, out, pos, p.length);
                pos += p.length;
            }
            return out;
        }

        private static byte[] concat(byte[] a, byte[] b) {
            if (a == null || a.length == 0) return b;
            if (b == null || b.length == 0) return a;
            byte[] out = new byte[a.length + b.length];
            System.arraycopy(a, 0, out, 0, a.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return out;
        }
    }

    // -----------------------------
    // Hex helpers
    // -----------------------------

    static final class Hex {
        private static final char[] CH = "0123456789abcdef".toCharArray();

        static String toHex(byte[] bytes) {
            if (bytes == null) return "";
            char[] out = new char[bytes.length * 2];
            int p = 0;
            for (byte b : bytes) {
                int v = b & 0xFF;
                out[p++] = CH[v >>> 4];
                out[p++] = CH[v & 0x0F];
            }
            return new String(out);
        }

        static byte[] fromHex(String hex) {
            String s = hex;
            if (s == null) s = "";
            if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
            int n = s.length();
            if (n == 0) return new byte[0];
            if (n % 2 != 0) s = "0" + s;
            int len = s.length() / 2;
            byte[] out = new byte[len];
            for (int i = 0; i < len; i++) {
                int hi = Character.digit(s.charAt(i * 2), 16);
                int lo = Character.digit(s.charAt(i * 2 + 1), 16);
                out[i] = (byte) ((hi << 4) | lo);
            }
            return out;
        }
    }

    // -----------------------------
    // Keccak-256 (pure Java)
    // -----------------------------

    // Ported conceptually from your workspace's AchanAX keccak helper.
    // It's complete and dependency-free so we can compute selectors without external libs.
    static final class Keccak {
        private static final long[] RC = new long[] {
                0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
                0x000000000000808bL, 0x8000000080000001L, 0x8000000000008081L, 0x8000000000008009L,
                0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
                0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
                0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
                0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
        };

        private static final int[][] ROT = new int[][]{
                { 0, 36, 3, 41, 18 },
                { 1, 44, 10, 45, 2 },
                { 62, 6, 43, 15, 61 },
                { 28, 55, 25, 21, 56 },
                { 27, 20, 39, 8, 14 }
        };

        static byte[] keccak256(byte[] input) {
            final int rateBytes = 136;
            long[] st = new long[25];

            int offset = 0;
            while (offset + rateBytes <= input.length) {
                absorbBlock(st, input, offset, rateBytes);
                keccakf(st);
                offset += rateBytes;
            }

