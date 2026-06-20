import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

public class Main {
    private static String currentDir = System.getProperty("user.dir");

    // Helper class to hold redirection information
    static class RedirectionInfo {
        String outputFile;
        int fd; // 1 for stdout, 2 for stderr
        boolean append; // true for >> or 1>>

        RedirectionInfo(String file, int fileDescriptor, boolean appendFlag) {
            this.outputFile = file;
            this.fd = fileDescriptor;
            this.append = appendFlag;
        }
    }

    // Run a pipeline with N stages. Supports streaming for external-only pipelines,
    // and a sequential capture fallback when builtins are involved.
    private static void runPipelineChain(List<String[]> stages, List<RedirectionInfo> redirects) {
        int n = stages.size();
        boolean anyBuiltin = false;
        for (String[] s : stages) if (isBuiltin(s[0])) anyBuiltin = true;

        if (anyBuiltin) {
            // Sequential fallback: capture output of each stage and feed to next
            byte[] cur = null;
            for (int i = 0; i < n; i++) {
                String[] words = stages.get(i);
                RedirectionInfo r = redirects.get(i);
                if (isBuiltin(words[0])) {
                    String out = executeBuiltinCapture(words);
                    cur = out.getBytes();
                } else {
                    String exec = getExecutable(words[0]);
                    if (exec == null) { System.out.println(words[0] + ": not found"); return; }
                    List<String> cmd = new ArrayList<>();
                    String progName = words[0].contains(File.separator) ? words[0] : new File(words[0]).getName();
                    cmd.add(progName);
                    for (int j = 1; j < words.length; j++) cmd.add(words[j]);
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.directory(new File(currentDir));
                    String foundDir = new File(exec).getParent();
                    String origPath = System.getenv("PATH"); if (origPath == null) origPath = "";
                    if (foundDir != null && !foundDir.isEmpty()) pb.environment().put("PATH", foundDir + File.pathSeparator + origPath);
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                    try {
                        Process p = pb.start();
                        if (cur != null) {
                            try (java.io.OutputStream os = p.getOutputStream()) { os.write(cur); }
                        } else {
                            p.getOutputStream().close();
                        }
                        java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
                        try (java.io.InputStream in = p.getInputStream()) {
                            byte[] buf = new byte[8192]; int rlen; while ((rlen = in.read(buf)) >= 0) if (rlen>0) bout.write(buf,0,rlen);
                        }
                        p.waitFor();
                        cur = bout.toByteArray();
                    } catch (IOException | InterruptedException e) {
                        System.out.println("pipeline stage failed: " + e.getMessage());
                        return;
                    }
                }
            }
            // Write final output according to last redirect
            RedirectionInfo lastR = redirects.get(n-1);
            if (lastR != null && lastR.fd == 1) {
                String out = cur == null ? "" : new String(cur);
                if (lastR.append) appendToFile(out, lastR.outputFile); else writeToFile(out, lastR.outputFile);
            } else {
                if (cur != null) System.out.print(new String(cur));
            }
            return;
        }

        // All external: streaming pipeline
        try {
            List<ProcessBuilder> pbs = new ArrayList<>();
            List<Process> procs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String[] words = stages.get(i);
                String exec = getExecutable(words[0]);
                if (exec == null) { System.out.println(words[0] + ": not found"); return; }
                List<String> cmd = new ArrayList<>();
                String progName = words[0].contains(File.separator) ? words[0] : new File(words[0]).getName();
                cmd.add(progName);
                for (int j = 1; j < words.length; j++) cmd.add(words[j]);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(new File(currentDir));
                // PATH
                String foundDir = exec != null ? new File(exec).getParent() : null;
                String origPath = System.getenv("PATH"); if (origPath == null) origPath = "";
                if (foundDir != null && !foundDir.isEmpty()) pb.environment().put("PATH", foundDir + File.pathSeparator + origPath);
                // redirects: middle stages use pipes
                if (i == 0) {
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                } else {
                    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                }
                if (i == n-1) {
                    RedirectionInfo rr = redirects.get(i);
                    if (rr != null && rr.fd == 1) {
                        File f = new File(rr.outputFile);
                        if (rr.append) pb.redirectOutput(ProcessBuilder.Redirect.appendTo(f)); else pb.redirectOutput(f);
                    } else pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                }
                RedirectionInfo rr2 = redirects.get(i);
                if (rr2 != null && rr2.fd == 2) {
                    File f = new File(rr2.outputFile);
                    if (rr2.append) pb.redirectError(ProcessBuilder.Redirect.appendTo(f)); else pb.redirectError(f);
                } else pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                pbs.add(pb);
            }

            // start processes
            for (ProcessBuilder pb : pbs) procs.add(pb.start());

            // connect pipelines
            List<Thread> pumps = new ArrayList<>();
            for (int i = 0; i < procs.size()-1; i++) {
                Process a = procs.get(i);
                Process b = procs.get(i+1);
                Thread pump = new Thread(() -> {
                    try (java.io.InputStream in = a.getInputStream(); java.io.OutputStream out = b.getOutputStream()) {
                        byte[] buf = new byte[8192]; int r; while ((r = in.read(buf)) >= 0) { if (r>0) out.write(buf,0,r); out.flush(); }
                    } catch (IOException e) {}
                    try { b.getOutputStream().close(); } catch (Exception e) {}
                });
                pump.start(); pumps.add(pump);
            }

            // wait for last, then wait others
            int lastIdx = procs.size()-1;
            procs.get(lastIdx).waitFor();
            for (Thread t : pumps) t.join();
            for (int i = 0; i < procs.size()-1; i++) procs.get(i).waitFor();
        } catch (IOException | InterruptedException e) {
            System.out.println("pipeline failed: " + e.getMessage());
        }
    }

    private static int nextJobId = 1;

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!sc.hasNextLine()) break;

            String input = sc.nextLine();
            String trimmed = input.stripLeading();
            if (trimmed.length() == 0) continue;

            String[] words = tokenize(trimmed);
            if (words.length == 0) continue;

            boolean background = false;
            if (words.length > 0 && "&".equals(words[words.length - 1])) {
                background = true;
                words = Arrays.copyOf(words, words.length - 1);
            }

            // Handle pipelines with one or more '|' tokens
            List<Integer> pipePositions = new ArrayList<>();
            for (int i = 0; i < words.length; i++) if ("|".equals(words[i])) pipePositions.add(i);
            if (!pipePositions.isEmpty()) {
                List<String[]> stages = new ArrayList<>();
                List<RedirectionInfo> redirects = new ArrayList<>();
                int start = 0;
                for (int pos : pipePositions) {
                    String[] part = Arrays.copyOfRange(words, start, pos);
                    RedirectionInfo r = parseRedirection(part);
                    if (r != null) part = removeRedirectionTokens(part);
                    stages.add(part);
                    redirects.add(r);
                    start = pos + 1;
                }
                String[] lastPart = Arrays.copyOfRange(words, start, words.length);
                RedirectionInfo rlast = parseRedirection(lastPart);
                if (rlast != null) lastPart = removeRedirectionTokens(lastPart);
                stages.add(lastPart);
                redirects.add(rlast);

                // validate
                boolean valid = true;
                for (String[] s : stages) if (s.length == 0) valid = false;
                if (!valid) continue;

                runPipelineChain(stages, redirects);
                continue;
            }

            // Parse redirection from tokens for non-pipeline commands
            RedirectionInfo redirect = parseRedirection(words);
            if (redirect != null) {
                // Remove redirection tokens from words
                words = removeRedirectionTokens(words);
            }

            if (words.length == 0) continue;
            String command = words[0];
            String[] rest = Arrays.copyOfRange(words, 1, words.length);

            if (Objects.equals(command, "exit")) {
                break;
            } else if (Objects.equals(command, "echo")) {
                String output = String.join(" ", rest);
                if (redirect != null && redirect.fd == 2) {
                    // Create the stderr redirection target even if echo produces no stderr
                    if (redirect.append) ensureFileExists(redirect.outputFile);
                    else ensureEmptyFile(redirect.outputFile);
                    System.out.println(output);
                } else if (redirect != null && redirect.fd == 1) {
                    if (redirect.append) appendToFile(output, redirect.outputFile);
                    else writeToFile(output, redirect.outputFile);
                } else {
                    System.out.println(output);
                }
            } else if (Objects.equals(command, "type")) {
                String output;
                if (rest.length > 0) {
                    output = type(rest[0]);
                } else {
                    output = "";
                }
                if (redirect != null && redirect.fd == 2) {
                    if (redirect.append) ensureFileExists(redirect.outputFile);
                    else ensureEmptyFile(redirect.outputFile);
                    System.out.println(output);
                } else if (redirect != null && redirect.fd == 1) {
                    if (redirect.append) appendToFile(output, redirect.outputFile);
                    else writeToFile(output, redirect.outputFile);
                } else {
                    System.out.println(output);
                }
            } else if (Objects.equals(command, "pwd")) {
                if (redirect != null && redirect.fd == 2) {
                    if (redirect.append) ensureFileExists(redirect.outputFile);
                    else ensureEmptyFile(redirect.outputFile);
                    System.out.println(currentDir);
                } else if (redirect != null && redirect.fd == 1) {
                    if (redirect.append) appendToFile(currentDir, redirect.outputFile);
                    else writeToFile(currentDir, redirect.outputFile);
                } else {
                    System.out.println(currentDir);
                }
            } else if (Objects.equals(command, "jobs")) {
                // Registered as a builtin; empty implementation for now
            } else if (Objects.equals(command, "cd")) {
                if (rest.length > 0) {
                    String target = rest[0];
                    File f;
                    if (target.equals("~") || target.startsWith("~/")) {
                        String home = System.getenv("HOME");
                        if (home == null || home.isEmpty()) {
                            home = System.getProperty("user.home");
                        }
                        if (home == null) {
                            System.out.println("cd: " + target + ": No such file or directory");
                            continue;
                        }
                        if (target.equals("~")) {
                            f = new File(home);
                        } else {
                            // target starts with ~/
                            f = new File(home, target.substring(2));
                        }
                    } else if (target.startsWith("/")) {
                        f = new File(target);
                    } else {
                        // relative path
                        f = new File(currentDir, target);
                    }

                    try {
                        File canon = f.getCanonicalFile();
                        if (canon.exists() && canon.isDirectory()) {
                            currentDir = canon.getCanonicalPath();
                        } else {
                            System.out.println("cd: " + target + ": No such file or directory");
                        }
                    } catch (IOException e) {
                        System.out.println("cd: " + target + ": No such file or directory");
                    }
                } else {
                    // no-op for now when no args
                }
            } else {
                String executablePath = getExecutable(command);
                if (executablePath != null) {
                    runExternal(command, executablePath, rest, redirect, background);
                } else {
                    System.out.println(command + ": not found");
                }
            }
        }
        sc.close();
    }

        // Tokenize input respecting single and double quotes and backslashes outside quotes.
        // - Whitespace outside quotes splits tokens (consecutive whitespace collapsed)
        // - Backslashes outside quotes escape the next character literally
        // - Text inside single quotes is taken literally (spaces preserved)
        // - Inside double quotes: backslash escapes ", \, $, `, newline; other backslashes are literal
        // - Adjacent quoted and unquoted parts are concatenated into a single token
        private static String[] tokenize(String line) {
            List<String> tokens = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inSingle = false;
            boolean inDouble = false;
            boolean escaped = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (escaped) {
                    cur.append(c);
                    escaped = false;
                    continue;
                }

                if (inSingle) {
                    if (c == '\'') {
                        inSingle = false;
                    } else {
                        cur.append(c);
                    }
                } else if (inDouble) {
                    if (c == '\\') {
                        // Look ahead to see if we need to escape the next character
                        if (i + 1 < line.length()) {
                            char next = line.charAt(i + 1);
                            // Backslash escapes these chars inside double quotes: " \ $ ` and newline
                            if (next == '"' || next == '\\' || next == '$' || next == '`' || next == '\n') {
                                escaped = true;
                            } else {
                                // Backslash is literal for other chars
                                cur.append(c);
                            }
                        } else {
                            // Trailing backslash is literal
                            cur.append(c);
                        }
                    } else if (c == '"') {
                        inDouble = false;
                    } else {
                        cur.append(c);
                    }
                } else {
                    if (c == '\\') {
                        escaped = true;
                    } else if (c == '\'') {
                        inSingle = true;
                    } else if (c == '"') {
                        inDouble = true;
                    } else if (Character.isWhitespace(c)) {
                        if (cur.length() > 0) {
                            tokens.add(cur.toString());
                            cur.setLength(0);
                        }
                        // else skip consecutive whitespace
                    } else {
                        cur.append(c);
                    }
                }
            }
            if (escaped) {
                // Trailing backslash escapes nothing, preserve it literally
                cur.append('\\');
            }
            // If still in single or double quote at end, take remaining text literally
            if (cur.length() > 0) tokens.add(cur.toString());
            return tokens.toArray(new String[0]);
        }

    private static void runExternal(String command, String executablePath, String[] args, RedirectionInfo redirect, boolean background) {
        try {
            List<String> cmd = new ArrayList<>();
            String progName = command.contains(File.separator) ? command : new File(command).getName();
            // Use program name as argv[0] so the executed program sees the basename
            cmd.add(progName);
            if (args.length > 0) {
                for (String arg : args) cmd.add(arg);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Set the child's working directory to the shell's currentDir
            pb.directory(new File(currentDir));
            // Ensure the directory containing the resolved executable is first in PATH
            String foundDir = new File(executablePath).getParent();
            String origPath = System.getenv("PATH");
            if (origPath == null) origPath = "";
            if (foundDir != null && !foundDir.isEmpty()) {
                pb.environment().put("PATH", foundDir + File.pathSeparator + origPath);
            }

            // Ensure parent directory exists for any redirection target
            if (redirect != null) {
                File outFile = new File(redirect.outputFile);
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
            }

            // Handle redirection
            if (redirect != null && redirect.fd == 1) {
                File f = new File(redirect.outputFile);
                if (redirect.append) {
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(f));
                } else {
                    pb.redirectOutput(f);
                }
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }
            if (redirect != null && redirect.fd == 2) {
                File f = new File(redirect.outputFile);
                if (redirect.append) {
                    pb.redirectError(ProcessBuilder.Redirect.appendTo(f));
                } else {
                    pb.redirectError(f);
                }
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

            Process process = pb.start();
            if (background) {
                int jobId = nextJobId++;
                System.out.println("[" + jobId + "] " + process.pid());
                return;
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.out.println(command + ": failed to execute (" + e.getMessage() + ")");
        }
    }

    // Run a pipeline of two external commands: left | right
    private static void runPipeline(String[] leftWords, String[] rightWords, RedirectionInfo leftRedirect, RedirectionInfo rightRedirect) {
        try {
            String leftCmd = leftWords[0];
            String rightCmd = rightWords[0];
            String leftExec = getExecutable(leftCmd);
            String rightExec = getExecutable(rightCmd);
            boolean leftIsBuiltin = isBuiltin(leftCmd);
            boolean rightIsBuiltin = isBuiltin(rightCmd);
            if (!leftIsBuiltin && leftExec == null) { System.out.println(leftCmd + ": not found"); return; }
            if (!rightIsBuiltin && rightExec == null) { System.out.println(rightCmd + ": not found"); return; }

            List<String> leftList = new ArrayList<>();
            String leftProg = leftCmd.contains(File.separator) ? leftCmd : new File(leftCmd).getName();
            leftList.add(leftProg);
            for (int i = 1; i < leftWords.length; i++) leftList.add(leftWords[i]);

            List<String> rightList = new ArrayList<>();
            String rightProg = rightCmd.contains(File.separator) ? rightCmd : new File(rightCmd).getName();
            rightList.add(rightProg);
            for (int i = 1; i < rightWords.length; i++) rightList.add(rightWords[i]);

            ProcessBuilder pb1 = new ProcessBuilder(leftList);
            ProcessBuilder pb2 = new ProcessBuilder(rightList);
            pb1.directory(new File(currentDir));
            pb2.directory(new File(currentDir));

            // set PATH so executables can find relative resources
            String foundDir1 = leftExec != null ? new File(leftExec).getParent() : null;
            String foundDir2 = rightExec != null ? new File(rightExec).getParent() : null;
            String origPath = System.getenv("PATH"); if (origPath == null) origPath = "";
            if (foundDir1 != null && !foundDir1.isEmpty()) pb1.environment().put("PATH", foundDir1 + File.pathSeparator + origPath);
            if (foundDir2 != null && !foundDir2.isEmpty()) pb2.environment().put("PATH", foundDir2 + File.pathSeparator + origPath);

            // Ensure parent dirs for any redirection targets
            if (leftRedirect != null) { File f = new File(leftRedirect.outputFile); File p = f.getParentFile(); if (p!=null && !p.exists()) p.mkdirs(); }
            if (rightRedirect != null) { File f = new File(rightRedirect.outputFile); File p = f.getParentFile(); if (p!=null && !p.exists()) p.mkdirs(); }

            // pb1 output -> pipe unless leftRedirect stdout specified
            if (leftRedirect != null && leftRedirect.fd == 1) {
                File f = new File(leftRedirect.outputFile);
                if (leftRedirect.append) pb1.redirectOutput(ProcessBuilder.Redirect.appendTo(f)); else pb1.redirectOutput(f);
            } else {
                pb1.redirectOutput(ProcessBuilder.Redirect.PIPE);
            }
            // pb1 stderr
            if (leftRedirect != null && leftRedirect.fd == 2) {
                File f = new File(leftRedirect.outputFile);
                if (leftRedirect.append) pb1.redirectError(ProcessBuilder.Redirect.appendTo(f)); else pb1.redirectError(f);
            } else pb1.redirectError(ProcessBuilder.Redirect.INHERIT);

            // pb2 input from pipe unless rightRedirect specifies stdin? we always pipe
            pb2.redirectInput(ProcessBuilder.Redirect.PIPE);
            // pb2 stdout -> file or inherit
            if (rightRedirect != null && rightRedirect.fd == 1) {
                File f = new File(rightRedirect.outputFile);
                if (rightRedirect.append) pb2.redirectOutput(ProcessBuilder.Redirect.appendTo(f)); else pb2.redirectOutput(f);
            } else pb2.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            // pb2 stderr
            if (rightRedirect != null && rightRedirect.fd == 2) {
                File f = new File(rightRedirect.outputFile);
                if (rightRedirect.append) pb2.redirectError(ProcessBuilder.Redirect.appendTo(f)); else pb2.redirectError(f);
            } else pb2.redirectError(ProcessBuilder.Redirect.INHERIT);

            // Handle combinations of builtin/external
            if (leftIsBuiltin && !rightIsBuiltin) {
                // start right external
                Process p2 = pb2.start();
                // write builtin output to p2 stdin or to file if leftRedirect.fd == 1
                if (leftRedirect != null && leftRedirect.fd == 1) {
                    if (leftRedirect.append) appendToFile(executeBuiltinCapture(leftWords), leftRedirect.outputFile);
                    else writeToFile(executeBuiltinCapture(leftWords), leftRedirect.outputFile);
                    p2.getOutputStream().close();
                } else {
                    // stream to p2 stdin
                    Thread t = new Thread(() -> {
                        try (java.io.OutputStream out = p2.getOutputStream()) {
                            runBuiltinToStream(leftWords, out);
                        } catch (Exception e) {}
                    });
                    t.start();
                }
                p2.waitFor();
                return;
            } else if (!leftIsBuiltin && rightIsBuiltin) {
                // left external, right builtin
                Process p1 = pb1.start();
                // drain p1 stdout (builtin may ignore stdin)
                Thread drain = new Thread(() -> {
                    try (java.io.InputStream in = p1.getInputStream()) {
                        byte[] buf = new byte[8192];
                        while (in.read(buf) >= 0) { /* discard */ }
                    } catch (IOException e) {}
                });
                drain.start();
                p1.waitFor();
                drain.join();
                // run right builtin, directing output according to rightRedirect
                if (rightRedirect != null && rightRedirect.fd == 1) {
                    if (rightRedirect.append) appendToFile(executeBuiltinCapture(rightWords), rightRedirect.outputFile);
                    else writeToFile(executeBuiltinCapture(rightWords), rightRedirect.outputFile);
                } else {
                    runBuiltinToStream(rightWords, System.out);
                }
                return;
            } else if (leftIsBuiltin && rightIsBuiltin) {
                // both builtins: run left into buffer, then run right
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                runBuiltinToStream(leftWords, buf);
                java.io.InputStream in = new java.io.ByteArrayInputStream(buf.toByteArray());
                // for simplicity, right builtin will ignore stdin; just execute and send to stdout or file
                if (rightRedirect != null && rightRedirect.fd == 1) {
                    if (rightRedirect.append) appendToFile(executeBuiltinCapture(rightWords), rightRedirect.outputFile);
                    else writeToFile(executeBuiltinCapture(rightWords), rightRedirect.outputFile);
                } else {
                    runBuiltinToStream(rightWords, System.out);
                }
                return;
            } else {
                // both external: previous behavior
                Process p1 = pb1.start();
                Process p2 = pb2.start();

                // Pump p1 stdout -> p2 stdin
                Thread pump = new Thread(() -> {
                    try (java.io.InputStream in = p1.getInputStream(); java.io.OutputStream out = p2.getOutputStream()) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) >= 0) {
                            if (r > 0) out.write(buf, 0, r);
                            out.flush();
                        }
                    } catch (IOException e) {
                        // ignore
                    } finally {
                        try { p2.getOutputStream().close(); } catch (Exception e) {}
                    }
                });
                pump.start();

                // wait for processes
                int s2 = p2.waitFor();
                try { p1.waitFor(); } catch (InterruptedException e) { p1.destroy(); }
                pump.join();
                return;
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("pipeline failed: " + e.getMessage());
        }
    }

    private static boolean isBuiltin(String cmd) {
        return Arrays.asList("exit", "echo", "type", "pwd", "cd", "jobs").contains(cmd);
    }

    // Execute a builtin and write its stdout to the provided OutputStream
    private static void runBuiltinToStream(String[] words, java.io.OutputStream outStream) {
        try {
            String cmd = words[0];
            String[] rest = Arrays.copyOfRange(words, 1, words.length);
            java.io.PrintWriter out = new java.io.PrintWriter(outStream, true);
            if (Objects.equals(cmd, "echo")) {
                out.println(String.join(" ", rest));
            } else if (Objects.equals(cmd, "type")) {
                String output = rest.length > 0 ? type(rest[0]) : "";
                out.println(output);
            } else if (Objects.equals(cmd, "pwd")) {
                out.println(currentDir);
            } else if (Objects.equals(cmd, "cd")) {
                // cd has no stdout
            } else if (Objects.equals(cmd, "exit")) {
                // exit handled by main loop; ignore here
            }
            out.flush();
        } catch (Exception e) {
            // ignore
        }
    }

    // Execute builtin and capture its stdout as string
    private static String executeBuiltinCapture(String[] words) {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        runBuiltinToStream(words, buf);
        return buf.toString();
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static String type(String command) {
        String[] commands = {"exit", "echo", "type", "pwd", "cd", "jobs"};
        for (String text : commands) {
            if (Objects.equals(text, command)) return command + " is a shell builtin";
        }
        String executablePath = getExecutable(command);
        if (executablePath != null) {
            return command + " is " + executablePath;
        }
        return command + ": not found";
    }

    public static String getExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] paths = pathEnv.split(File.pathSeparator);
        for (String path : paths) {
            if (path.length() == 0) path = ".";
            File file = new File(path, command);
            try {
                if (file.exists() && Files.isExecutable(file.toPath())) {
                    return file.getAbsolutePath();
                }
            } catch (Exception e) {
                // ignore and continue
            }
        }
        return null;
    }

    // Parse redirection operators (>, 1>, >>, 1>>, 2>, 2>>) from the token list
    private static RedirectionInfo parseRedirection(String[] words) {
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (">".equals(w) || "1>".equals(w) || ">>".equals(w) || "1>>".equals(w)) {
                boolean append = ">>".equals(w) || "1>>".equals(w);
                if (i + 1 < words.length) {
                    return new RedirectionInfo(words[i + 1], 1, append);
                }
            } else if ("2>".equals(w) || "2>>".equals(w)) {
                boolean append = "2>>".equals(w);
                if (i + 1 < words.length) {
                    return new RedirectionInfo(words[i + 1], 2, append);
                }
            }
        }
        return null;
    }

    // Remove redirection operators and filenames from the token list
    private static String[] removeRedirectionTokens(String[] words) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            if (">".equals(words[i]) || "1>".equals(words[i]) || ">>".equals(words[i]) || "1>>".equals(words[i]) || "2>".equals(words[i]) || "2>>".equals(words[i])) {
                // Skip the operator and the following filename
                i++; // skip filename
            } else {
                result.add(words[i]);
            }
        }
        return result.toArray(new String[0]);
    }

    // Write a string to a file, creating it if it doesn't exist, overwriting if it does
    private static void writeToFile(String content, String filename) {
        try {
            File f = new File(filename);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileWriter writer = new FileWriter(f);
            writer.write(content);
            writer.write("\n");
            writer.close();
        } catch (IOException e) {
            System.err.println("Error writing to file: " + e.getMessage());
        }
    }

    // Append a string to a file, creating it if it doesn't exist
    private static void appendToFile(String content, String filename) {
        try {
            File f = new File(filename);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileWriter writer = new FileWriter(f, true);
            writer.write(content);
            writer.write("\n");
            writer.close();
        } catch (IOException e) {
            System.err.println("Error appending to file: " + e.getMessage());
        }
    }

    // Create or truncate a file to zero length, ensuring parent directories exist
    private static void ensureEmptyFile(String filename) {
        try {
            File f = new File(filename);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            // Create or truncate file
            new java.io.FileOutputStream(f, false).close();
        } catch (IOException e) {
            System.err.println("Error creating file: " + e.getMessage());
        }
    }

    // Ensure a file exists; do not modify its contents if it already exists
    private static void ensureFileExists(String filename) {
        try {
            File f = new File(filename);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (!f.exists()) new java.io.FileOutputStream(f, false).close();
        } catch (IOException e) {
            System.err.println("Error creating file: " + e.getMessage());
        }
    }
}
