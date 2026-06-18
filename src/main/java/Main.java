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

            String st = sc.nextLine();
            if (st.equals("exit")) {
                break;
            }

            System.out.println(st + ": command not found");
        }
    }
}
