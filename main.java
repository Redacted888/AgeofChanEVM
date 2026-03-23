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
