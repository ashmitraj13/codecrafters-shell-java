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

            // Parse redirection from tokens
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
                    runExternal(command, executablePath, rest, redirect);
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

    private static void runExternal(String command, String executablePath, String[] args, RedirectionInfo redirect) {
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
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.out.println(command + ": failed to execute (" + e.getMessage() + ")");
        }
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static String type(String command) {
        String[] commands = {"exit", "echo", "type", "pwd", "cd"};
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
