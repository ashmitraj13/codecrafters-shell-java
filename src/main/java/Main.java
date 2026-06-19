import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

public class Main {
    private static String currentDir = System.getProperty("user.dir");
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!sc.hasNextLine()) break;

            String input = sc.nextLine();
            String trimmed = input.stripLeading();
            if (trimmed.length() == 0) continue;

            String[] words = trimmed.split("\\s+");
            String command = words[0];
            String[] rest = Arrays.copyOfRange(words, 1, words.length);

            if (Objects.equals(command, "exit")) {
                break;
            } else if (Objects.equals(command, "echo")) {
                System.out.println(String.join(" ", rest));
            } else if (Objects.equals(command, "type")) {
                if (rest.length > 0) {
                    System.out.println(type(rest[0]));
                } else {
                    System.out.println();
                }
            } else if (Objects.equals(command, "pwd")) {
                System.out.println(currentDir);
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
                    runExternal(command, executablePath, rest);
                } else {
                    System.out.println(command + ": not found");
                }
            }
        }
        sc.close();
    }

    private static void runExternal(String command, String executablePath, String[] args) {
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

            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
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
}