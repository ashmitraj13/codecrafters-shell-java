import java.util.Scanner;
import java.io.File;
import java.nio.file.Files;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!sc.hasNextLine()) {
                break;
            }

            String input = sc.nextLine();
            String trimmed = input.stripLeading();
            if (trimmed.equals("exit")) {
                break;
            }

            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length > 0 && parts[0].equals("type")) {
                if (parts.length > 1) {
                    String target = parts[1];
                    if (target.equals("echo") || target.equals("exit") || target.equals("type")) {
                        System.out.println(target + " is a shell builtin");
                    } else {
                        String pathEnv = System.getenv("PATH");
                        if (pathEnv == null) pathEnv = "";
                        boolean found = false;
                        for (String dir : pathEnv.split(File.pathSeparator)) {
                            if (dir.length() == 0) dir = ".";
                            File candidate = new File(dir, target);
                            if (candidate.exists()) {
                                try {
                                    if (Files.isExecutable(candidate.toPath())) {
                                        System.out.println(target + " is " + candidate.getAbsolutePath());
                                        found = true;
                                        break;
                                    } else {
                                        // exists but not executable: skip
                                        continue;
                                    }
                                } catch (Exception e) {
                                    // On any error checking executability, skip this candidate
                                    continue;
                                }
                            }
                        }
                        if (!found) {
                            System.out.println(target + ": not found");
                        }
                    }
                } else {
                    System.out.println();
                }
            } else if (parts.length > 0 && parts[0].equals("echo")) {
                if (parts.length > 1) {
                    System.out.println(parts[1]);
                } else {
                    System.out.println();
                }
            } else {
                // Attempt to execute external program found in PATH
                String[] fullParts = trimmed.split("\\s+");
                if (fullParts.length == 0 || fullParts[0].length() == 0) {
                    continue;
                }
                String cmdName = fullParts[0];
                String pathEnv = System.getenv("PATH");
                if (pathEnv == null) pathEnv = "";
                File executable = null;

                // If the command contains a file separator, treat as a path
                if (cmdName.contains(File.separator)) {
                    File candidate = new File(cmdName);
                    if (candidate.exists() && Files.isExecutable(candidate.toPath())) {
                        executable = candidate;
                    }
                } else {
                    for (String dir : pathEnv.split(File.pathSeparator)) {
                        if (dir.length() == 0) dir = ".";
                        File candidate = new File(dir, cmdName);
                        if (candidate.exists()) {
                            try {
                                if (Files.isExecutable(candidate.toPath())) {
                                    executable = candidate;
                                    break;
                                } else {
                                    continue;
                                }
                            } catch (Exception e) {
                                continue;
                            }
                        }
                    }
                }

                if (executable != null) {
                    try {
                        java.util.List<String> cmd = new java.util.ArrayList<>();
                        cmd.add(executable.getAbsolutePath());
                        if (fullParts.length > 1) {
                            for (int i = 1; i < fullParts.length; i++) cmd.add(fullParts[i]);
                        }
                        ProcessBuilder pb = new ProcessBuilder(cmd);
                        pb.inheritIO();
                        Process p = pb.start();
                        p.waitFor();
                    } catch (Exception e) {
                        System.out.println("Failed to execute " + cmdName + ": " + e.getMessage());
                    }
                } else {
                    System.out.println(input + ": command not found");
                }
            }
        }
    }
}