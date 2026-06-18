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
                System.out.println(input + ": command not found");
            }
        }
    }
}