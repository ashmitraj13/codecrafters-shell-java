import java.util.Scanner;

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
            if (parts.length > 0 && parts[0].equals("echo")) {
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