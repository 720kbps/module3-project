import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Scanner;

public class ScubaTUI {
    private static String SERVER_IP = "netsys.ewi.utwente.nl";
    private static int SERVER_PORT = 8954;
    private static int frequency = 5801;

    public void runTUI(){
        System.out.println("Welcome to ScubaTUI!");
        System.out.println("Please enter your name: ");
        Scanner scanner = new Scanner(System.in);
        String name = scanner.nextLine();
        System.out.println("Hello, " + name + "!");
        MyProtocol protocol = new MyProtocol(SERVER_IP, SERVER_PORT, frequency);
        try{
            ByteBuffer temp = ByteBuffer.allocate(1024);
            int read = 0;
            while (true) {
                read = System.in.read (temp.array());
                System.out.println("Read: " + read + " bytes from stdin");
                protocol.sendPackets(read, temp);
            }
        } catch (InterruptedException | IOException e){ System.exit(2); }
    }
    public static void main(String[] args) {
        ScubaTUI tui = new ScubaTUI();
        tui.runTUI();
    }
}
