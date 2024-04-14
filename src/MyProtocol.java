import client.*;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class MyProtocol {
    private static String SERVER_IP = "netsys.ewi.utwente.nl";
    private static int SERVER_PORT = 8954;
    private static int frequency = 5800;
    String token = "java-53-ME854K6ZFTSIXDHC2V";

    private static final byte src = (byte) new Random().nextInt(255);
    // Address from 0 to 254
    // Address 255 or byte -1 is used for broadcast to all nodes (neighboring)

    private boolean freeLink = true; // Keeps the link state

    private List<RoutingInfo> routingTable = new ArrayList<>();
    private BlockingQueue<Message> receivedQueue;
    private BlockingQueue<Message> sendingQueue;

    public MyProtocol (String server_ip, int server_port, int frequency)
            throws InterruptedException {

        receivedQueue = new LinkedBlockingQueue<Message>();
        sendingQueue = new LinkedBlockingQueue<Message>();

        new Client (SERVER_IP, SERVER_PORT, frequency, token, receivedQueue, sendingQueue);
        new receiveThread (receivedQueue).start();

        Thread.sleep(1000); // wait 1 second for framework
        chatInit();

        try{
            ByteBuffer temp = ByteBuffer.allocate(1024);
            int read = 0;
            while (true) {
                read = System.in.read (temp.array());
                System.out.println("Read: " + read + " bytes from stdin");
                sendPackets (read, temp);
            }
        } catch (InterruptedException | IOException e) { System.exit(2); }
    }

    private void chatInit() {
        System.out.println("Your source address is: " + src + "\nChoose a username please");
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter username: ");
        String username = scanner.nextLine();
        while (!isValidUsername(username)) {
            System.out.print("Invalid username(>25)\nEnter new username: ");
            username = scanner.nextLine();
        }
        initRoutingMessage(username);
    }

    public static String getCurrentTime() {
        LocalTime currentTime = LocalTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("[HH:mm:ss]");
        return currentTime.format(formatter);
    }

    public static void main (String args[]) throws InterruptedException {
        if (args.length > 0) frequency = Integer.parseInt(args[0]);
        new MyProtocol (SERVER_IP, SERVER_PORT, frequency);
    }

    private class receiveThread extends Thread {
        private BlockingQueue<Message> receivedQueue;

        public receiveThread(BlockingQueue<Message> receivedQueue) {
            super();
            this.receivedQueue = receivedQueue;
        }

        // TODO: Adjust for use in chat - only use the bellow for debugging
        public void printByteBuffer (ByteBuffer bytes, int bytesLength) {
            System.out.print ("[" +getCurrentTime() + "] ");
            for (int j = 0; j < 6; j++)
            {
                byte charByte = bytes.get(j);
                System.out.print ((int) charByte + " ");
            }
            for (int i = 6; i < bytesLength; i++) {
                byte charByte = bytes.get(i);
                System.out.print ((char) charByte + "");
            }
            System.out.println();
        }

        public void run() {
            while (true) {
                try {
                    Message m = receivedQueue.take();
                    if (m.getType() == MessageType.BUSY) {
                        // System.out.println("BUSY");
                        freeLink = false;
                    } else if (m.getType() == MessageType.FREE) {
                        // System.out.println("FREE");
                        freeLink = true;
                    } else if (m.getType() == MessageType.DATA) {
                        freeLink = true;
                        System.out.print("DATA: ");
                        printByteBuffer (m.getData(), m.getData().capacity());
                        routingUpdate (m.getData());
                    } else if (m.getType() == MessageType.DATA_SHORT) {
                        // System.out.print("DATA_SHORT: ");
                        // printByteBuffer(m.getData(), m.getData().capacity());
                        freeLink = false;
                    } else if (m.getType() == MessageType.DONE_SENDING) {
                        System.out.println("DONE_SENDING");
                    } else if (m.getType() == MessageType.HELLO) {
                        System.out.println("HELLO");
                    } else if (m.getType() == MessageType.SENDING) {
                        System.out.println("SENDING");
                    } else if (m.getType() == MessageType.END) {
                        System.out.println("END");
                        System.exit(0);
                    } else if (m.getType() == MessageType.TOKEN_ACCEPTED) {
                        System.out.println("Token Valid!");
                    } else if (m.getType() == MessageType.TOKEN_REJECTED) {
                        System.out.println("Token Rejected!");
                    }
                } catch (InterruptedException e) {
                    System.err.println("Failed to take from queue: " + e);
                }
            }
        }
    }

    public void sendPackets (int read, ByteBuffer temp) throws InterruptedException {
        int new_line_offset = 0;
        if (read > 0) {
            if (temp.get(read - 1) == '\n' || temp.get(read - 1) == '\r') {
                new_line_offset = 1;
            }
            if (read > 1 && (temp.get(read - 2) == '\n' || temp.get(read - 2) == '\r')) {
                new_line_offset = 2;
            }
            Message msg;
            int position=0;
            while (read > 26) {
                ByteBuffer toSend = ByteBuffer.allocate(32);
                headerBuilder (toSend, src, (byte) 0, 0, 0,
                               0, false, false, false, 26);
                toSend.put(temp.array(), position, 26);
                System.out.println(read);
                msg = new Message(MessageType.DATA, toSend);
                sendPacketsHelper(msg);
                position += 26;
                read -= 26;
            }
            ByteBuffer toSend = ByteBuffer.allocate(32);
            headerBuilder (toSend, src, (byte) 0, 0, 0,
                           0, true, false, false, read);
            toSend.put(temp.array(), position, read - new_line_offset);
            msg = new Message(MessageType.DATA, toSend);
            sendPacketsHelper(msg);
        }
    }

    public void headerBuilder (ByteBuffer packet, byte source, byte destination, int seq, int ack,
                               int TTL, boolean FIN, boolean RMS, boolean INIT, int length) {
        packet.put(source); // Source Address
        packet.put(destination); // Destination Address
        packet.put((byte) seq); // Sequence number
        packet.put((byte) ack); // Acknowledgment number
        packet.put((byte) ((TTL << 4) | (INIT ? 0b100 : 0) | (FIN ? 0b10 : 0) | (RMS ? 0b01 : 0)));
        packet.put((byte) length); // Payload length
    }

    public void sendPacketsHelper (Message msg) throws InterruptedException {
        // CA (collision avoidance) implementation
        while (true) {
            if (freeLink) {
                Thread.sleep(new Random().nextInt(500)); // Random back off
                sendingQueue.put(msg);
                break;
            }
            Thread.sleep(1000); // Time slots of 1 second
        }
    }

    public void initRoutingMessage (String username) {
        routingTable.add(new RoutingInfo(username, src, src));
        ByteBuffer routingMessage = ByteBuffer.allocate(32);
        headerBuilder(routingMessage, src, (byte) 255, 0, 0, 0,
                      false, true, true, username.length());
        for (char c : username.toCharArray()) { routingMessage.put((byte) c); }
        Message msg = new Message(MessageType.DATA, routingMessage);
        try { sendPacketsHelper(msg); }
        catch (InterruptedException e) {}
    }

    public boolean isValidUsername (String username) { return username.length() <= 25; }

    public boolean isInRoutingTable (byte address) {
        for (int i = 0; i < routingTable.size(); i++) {
            if (routingTable.get(i).address == address) {
                return true;
            }
        }
        return false;
    }

    public void printRoutingTable () {
        for (RoutingInfo routingInfo : routingTable) {
            System.out.print(routingInfo.username + " ");
            System.out.print(routingInfo.address + " ");
            System.out.println(routingInfo.nextHopAddress);
        }
    }

    public void routingUpdate (ByteBuffer packet) {
        ByteBuffer routingMessage = ByteBuffer.allocate(32);
        String username = "";
        for (int i = 0; i < 6 + (int) packet.get(5); i++) {
            if (i > 5) username += (char) packet.get(i);
            if (i == 4)  routingMessage.put((byte) 1);
            else routingMessage.put(packet.get(i));
        }
        if (packet.get(4) == 5 && !isInRoutingTable (packet.get(0))) {
            routingTable.add(new RoutingInfo(username, packet.get(0), src));
            routingMessage.put(src);
            Message msg = new Message(MessageType.DATA, routingMessage);
            System.out.println("SENT");
            try { sendPacketsHelper(msg); }
            catch (InterruptedException e) {}
            printRoutingTable();
        } else if (packet.get(4) == 1 && !isInRoutingTable (packet.get(0))) {
            int ss = (int) packet.get(5) + 1;
            routingTable.add(new RoutingInfo(username, packet.get(0),
                                                 packet.get(ss)));
            Message msg = new Message(MessageType.DATA, packet);
            try { sendPacketsHelper(msg); }
            catch (InterruptedException e) {}
            printRoutingTable();
        }
    }
}


