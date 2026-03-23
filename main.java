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
