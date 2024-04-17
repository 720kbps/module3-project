import client.Client;
import client.Message;
import client.MessageType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MyProtocol {
    private static String SERVER_IP = "netsys.ewi.utwente.nl";
    private static int SERVER_PORT = 8954;
    private static int frequency = 5800;
    String token = "java-53-ME854K6ZFTSIXDHC2V";

    // Source address from 0 to 254
    // Source address 255 or byte -1 is used for broadcast to all nodes (neighboring)
    private static final byte SRC = (byte) (1 + new Random().nextInt(254));

    private static String srcUsername = ""; // Username of the source from above

    private boolean freeLink = true; // Keeps the link state ~ for CA

    private Set<ByteBuffer> packetSet = new HashSet<>();

    private List<RoutingInfo> routingTable = new ArrayList<>();

    private List<IncomingInfo> incomingBuffer = new ArrayList<>();
    private int receivedAck = 0;
    private int sentSeq = 255;

    private BlockingQueue<Message> receivedQueue;
    private BlockingQueue<Message> sendingQueue;

    public MyProtocol(String server_ip, int server_port, int frequency)
            throws InterruptedException {

        receivedQueue = new LinkedBlockingQueue<Message>();
        sendingQueue = new LinkedBlockingQueue<Message>();

        new Client(SERVER_IP, SERVER_PORT, frequency, token, receivedQueue, sendingQueue);
        new receiveThread(receivedQueue).start();

        routingMessage.start(); // Routing message thread
        clearRoutingTable.start(); // Routing update thread

        Thread.sleep(1000); // wait 1 second for framework
        chatInit(); // Chat initiation

        mainChat();

    }

    private void chatInit() {
        System.out.println("⁶\uD80C\uDD53");
        System.out.println("Your source address is: " + SRC + "\nChoose a username please");
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter username: ");
        String username = scanner.nextLine();
        while (!isValidUsername(username)) {
            System.out.print("Invalid username(>25)\nEnter new username: ");
            username = scanner.nextLine();
        }
        srcUsername = username;
        routingTable.add(new RoutingInfo(srcUsername, SRC, SRC));
        initRoutingMessage();
    }


    private boolean isValidUsername(String username) {
        return username.length() <= 25;
    }

    private void mainChat() {
        while (true) {
            System.out.print("Enter command: ");
            Scanner scanner = new Scanner(System.in);
            String command = scanner.nextLine();
            if (Objects.equals(command, "list")) {
                System.out.println("\nOnline users:");
                for (RoutingInfo r : routingTable) {
                    System.out.println(r.username);
                }
                System.out.println();
            } else if (Objects.equals(command, "message")) {
                System.out.print("\nTo: ");
                String username = scanner.nextLine();
                byte dst = getAddress(username);
                while (dst == -1 || Objects.equals(username, srcUsername)) {
                    System.out.println("\nUsername not found, try again");
                    if (Objects.equals(username, srcUsername)) {
                        System.out.println("Are you trying to send messages to yourself?");
                    }
                    System.out.print("\nTo: ");
                    username = scanner.nextLine();
                    dst = getAddress(username);
                }
                byte nextHop = getNextHopAddress(username);
                System.out.println(nextHop);
                System.out.println("Message:\n");
                try {
                    ByteBuffer temp = ByteBuffer.allocate(1024);
                    int read;
                    read = System.in.read(temp.array());
                    if (read < 1024) {
                        sendPackets(read, temp, dst, nextHop, SRC);
                    } else {
                        System.out.println("Character limit 1024 exceeded");
                    }
                } catch (InterruptedException | IOException e) {
                    System.exit(2);
                }
            }
        }
    }

    private static String getCurrentTime() {
        LocalTime currentTime = LocalTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("[HH:mm:ss]");
        return currentTime.format(formatter);
    }

    public static void main(String args[]) throws InterruptedException {
        if (args.length > 0) {
            frequency = Integer.parseInt(args[0]);
        }
        new MyProtocol(SERVER_IP, SERVER_PORT, frequency);
    }

    private class receiveThread extends Thread {
        private BlockingQueue<Message> receivedQueue;

        public receiveThread(BlockingQueue<Message> receivedQueue) {
            super();
            this.receivedQueue = receivedQueue;
        }

        private void printByteBuffer(ByteBuffer bytes, int bytesLength) {
            System.out.print("[" + getCurrentTime() + "] ");
            for (int j = 0; j < 6; j++) {
                byte charByte = bytes.get(j);
                System.out.print((int) charByte + " ");
            }
            for (int i = 6; i < bytesLength; i++) {
                byte charByte = bytes.get(i);
                System.out.print((char) charByte + "");
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
                        // System.out.print("DATA: ");
                        // printByteBuffer (m.getData(), m.getData().capacity());
                        if (m.getData().get(1) == -1) {
                            routingUpdate(m.getData());
                        }
                        receivePackets(m.getData());
                    } else if (m.getType() == MessageType.DATA_SHORT) {
                        //System.out.print("DATA_SHORT: ");
                        //printByteBuffer(m.getData(), m.getData().capacity());
                        freeLink = false;
                    } else if (m.getType() == MessageType.DONE_SENDING) {
                        freeLink = true;
                        // System.out.println("DONE_SENDING");
                    } else if (m.getType() == MessageType.HELLO) {
                        System.out.println("HELLO");
                    } else if (m.getType() == MessageType.SENDING) {
                        // System.out.println("SENDING");
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

    private void sendPackets(int read, ByteBuffer temp, byte dst, byte frw, byte src)
            throws InterruptedException {
        System.out.println();
        int new_line_offset = 0;
        if (read > 0) {
            if (temp.get(read - 1) == '\n' || temp.get(read - 1) == '\r') {
                new_line_offset = 1;
            }
            if (read > 1 && (temp.get(read - 2) == '\n' || temp.get(read - 2) == '\r')) {
                new_line_offset = 2;
            }
            Message msg;
            int position = 0;
            int seq = 1 + new Random().nextInt(200);
            while (read > 26) {
                sentSeq = seq;
                seq++;
                ByteBuffer toSend = ByteBuffer.allocate(32);
                headerBuilder(toSend, src, dst, seq, frw, (frw != SRC),
                              false, false, false, 26);
                toSend.put(temp.array(), position, 26);
                msg = new Message(MessageType.DATA, toSend);
                stopAndWaitSend(msg);
                position += 26;
                read -= 26;
            }
            sentSeq = seq;
            seq++;
            ByteBuffer toSend = ByteBuffer.allocate(32);
            headerBuilder(toSend, src, dst, seq, frw, (frw != SRC),
                          true, false, false, read);
            toSend.put(temp.array(), position, read - new_line_offset);
            msg = new Message(MessageType.DATA, toSend);
            stopAndWaitSend(msg);
            sentSeq = seq;
        }
    }

    private void headerBuilder(ByteBuffer packet, byte source, byte destination, int seq, int ack,
                              boolean FRW, boolean FIN, boolean RMS, boolean INIT,
                              int length) {
        packet.put(source);
        packet.put(destination);
        packet.put((byte) seq);
        packet.put((byte) ack);
        packet.put((byte) ((0) | (FRW ? 0b1000 : 0) | (INIT ? 0b100 : 0) | (FIN ? 0b10 : 0) |
                (RMS ? 0b01 : 0)));
        packet.put((byte) length);
    }

    private void sendPacketsHelper(Message msg) throws InterruptedException {
        // CA (collision avoidance) implementation
        while (true) {
            if (freeLink) {
                sendingQueue.put(msg);
                try {
                    Thread.sleep(new Random().nextInt(200) + 500); // Random back off
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }
                break;
            }
            try {
                Thread.sleep(1000); // Time slots of 1 second
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /* ROUTING */

    private void initRoutingMessage() {
        ByteBuffer routingMessage = ByteBuffer.allocate(32);
        headerBuilder(routingMessage, SRC, (byte) 255, 0, 0,
                      false, false, true, true, srcUsername.length());
        for (char c : srcUsername.toCharArray()) {
            routingMessage.put((byte) c);
        }
        Message msg = new Message(MessageType.DATA, routingMessage);
        try {
            sendPacketsHelper(msg);
        } catch (InterruptedException e) {
        }
    }

    Thread routingMessage = new Thread(() -> {
        while (true) {
            // send the initial routing message
            if (!Objects.equals(srcUsername, "")) {
                initRoutingMessage();
            }
            // wait 5 + random < 2 seconds ~ before the next update
            try {
                Thread.sleep(4000);
                Thread.sleep(new Random().nextInt(1000));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    });

    Thread clearRoutingTable = new Thread(() -> {
        while (true) {
            try {
                Thread.sleep(120000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            routingTable.clear();
            routingTable.add(new RoutingInfo(srcUsername, SRC, SRC));
        }
    });

    private boolean isInRoutingTable(byte address) {
        for (RoutingInfo r : routingTable) {
            if (r.address == address) {
                return true;
            }
        }
        return false;
    }

    private void printRoutingTable() {
        for (RoutingInfo r : routingTable) {
            System.out.print(r.username + " ");
            System.out.print(r.address + " ");
            System.out.println(r.nextHopAddress);
        }
        System.out.println();
    }

    private void routingUpdate(ByteBuffer packet) {
        ByteBuffer routingMessage = ByteBuffer.allocate(32);
        String username = "";
        for (int i = 0; i < 6 + (int) packet.get(5); i++) {
            if (i > 5) {
                username += (char) packet.get(i);
            }
            if (i == 4) {
                routingMessage.put((byte) 1);
            } else {
                routingMessage.put(packet.get(i));
            }
        }
        if (packet.get(4) == 5 && !isInRoutingTable(packet.get(0))) {
            routingTable.add(new RoutingInfo(username, packet.get(0), SRC));
            routingMessage.put(SRC);
            Message msg = new Message(MessageType.DATA, routingMessage);
            try {
                sendPacketsHelper(msg);
            } catch (InterruptedException e) {
            }
        } else if (packet.get(4) == 1 && !isInRoutingTable(packet.get(0))) {
            int ss = (int) packet.get(5) + 6;
            routingTable.add(new RoutingInfo(username, packet.get(0), packet.get(ss)));
            Message msg = new Message(MessageType.DATA, packet);
            try {
                sendPacketsHelper(msg);
            } catch (InterruptedException e) {
            }
        }
    }

    private String getUsername(byte address) {
        for (RoutingInfo r : routingTable) {
            if (r.address == address) {
                return r.username;
            }
        }
        return "Unknown";
    }


    private byte getAddress(String username) {
        for (RoutingInfo r : routingTable) {
            if (Objects.equals(r.username, username)) {
                return r.address;
            }
        }
        return (byte) 255;
    }

    private byte getNextHopAddress(String username) {
        for (RoutingInfo r : routingTable) {
            if (Objects.equals(r.username, username)) {
                return r.nextHopAddress;
            }
        }
        return (byte) 255;
    }

    /* ROUTING */

    private void receivePackets(ByteBuffer packet) throws InterruptedException {
        if (packet.get(1) == SRC || packet.get(3) == SRC) {
            if (packet.get(2) != 0) {
                ByteBuffer ack = ByteBuffer.allocate(32);
                int tack = (packet.get(2) & 0xFF);
                byte dst = packet.get(0);
                if(packet.get(4) < 8 && packet.get(3) != (packet.get(0) & 0xFF)) {
                    dst = packet.get(3);
                }
                headerBuilder(ack, SRC, dst, 0, tack, false, false,
                              false, false, 0);
                Message msg = new Message(MessageType.DATA, ack);
                sendPacketsHelper(msg);
                if (!packetSet.contains(packet)) {
                    packetSet.add(packet);
                    int q = isInIncomingBuffer(packet.get(0), packet.get(2) & 0xFF);
                    if (packet.get(4) == 2 || packet.get(4) == 10) {
                        if (q != -1) {
                            incomingBuffer.get(q).message = buildMessage(incomingBuffer.get(q).message, packet);
                            incomingBuffer.get(q).fullMessageArrived = true;
                            if (packet.get(4) == 10) {
                                qq = q;
                                qdst = packet.get(1);
                                forwardMessage.start();
                            }
                        } else {
                            incomingBuffer.add(
                                    new IncomingInfo(getUsername(packet.get(0)), packet.get(0),
                                                     (packet.get(2) & 0xFF) + 1, "",
                                                     (packet.get(4) == 10), true));
                            incomingBuffer.get(incomingBuffer.size() - 1).message =
                                    buildMessage(incomingBuffer.get(incomingBuffer.size() - 1).message,
                                                 packet);
                            q = incomingBuffer.size() - 1;
                            if (packet.get(4) == 10) {
                                qq = q;
                                qdst = packet.get(1);
                                forwardMessage.start();
                            }
                        }
                    } else if (packet.get(4) == 0 || packet.get(4) == 8) {
                        if (q != -1) {
                            incomingBuffer.get(q).message = buildMessage(incomingBuffer.get(q).message, packet);
                            incomingBuffer.get(q).seq++;
                        } else {
                            incomingBuffer.add(
                                    new IncomingInfo(getUsername(packet.get(0)), packet.get(0),
                                                     (packet.get(2) & 0xFF) + 1, "",
                                                     (packet.get(4) == 8), false));
                            incomingBuffer.get(incomingBuffer.size() - 1).message =
                                    buildMessage(incomingBuffer.get(incomingBuffer.size() - 1).message,
                                                 packet);
                        }
                    }
                }
            } else if (packet.get(2) == 0) {
                receivedAck = (packet.get(3) & 0xFF);
            }
        }
        //incomingMessages();
        printIncomingBuffer();
    }

    private int qq;
    private byte qdst;

    Thread forwardMessage = new Thread(() -> {
        ByteBuffer forwardedMessage = ByteBuffer.allocate(1024);
        forwardedMessage.put(incomingBuffer.get(qq).message.getBytes());
        byte frw = getNextHopAddress(incomingBuffer.get(qq).username);
        byte src = getAddress(incomingBuffer.get(qq).username);
        try {
            sendPackets(incomingBuffer.get(qq).message.length(), forwardedMessage, qdst, frw, src);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    });

    private void forwardMessage(int q, byte dst) throws InterruptedException {

    }

    private int isInIncomingBuffer(byte address, int seq) {
        for (int i = 0; i < incomingBuffer.size(); i++) {
            if (incomingBuffer.get(i).address == address && incomingBuffer.get(i).seq == seq) {
                return i;
            }
        }
        return -1;
    }

    private void incomingMessages() {
        Iterator<IncomingInfo> iterator = incomingBuffer.iterator();
        while (iterator.hasNext()) {
            IncomingInfo i = iterator.next();
            if (i.fullMessageArrived && !i.toBeForwarded) {
                i.message = i.message.substring(0, i.message.length() - 1);
                System.out.println(getCurrentTime() + " " + i.username + ": " + i.message);
                // iterator.remove();
            }
        }
    }

    private String buildMessage(String message, ByteBuffer packet) {
        StringBuilder messageBuilder = new StringBuilder(message);
        for (int i = 6; i < 6 + packet.get(5); i++) {
            messageBuilder.append((char) packet.get(i));
        }
        message = messageBuilder.toString();
        return message;
    }

    private void printIncomingBuffer() {
        for (IncomingInfo i : incomingBuffer) {
            System.out.print(i.username + " ");
            System.out.print(i.address + " ");
            System.out.print(i.seq + " ");
            System.out.print(i.message + " ");
            System.out.print(i.toBeForwarded + " ");
            System.out.println(i.fullMessageArrived);
        }
        System.out.println();
    }

    private void stopAndWaitSend(Message msg) throws InterruptedException {
        int delay = 3000;
        while (true) {
            if (delay == 3000) {
                delay = 0;
                sendPacketsHelper(msg);
            }
            if (receivedAck == sentSeq + 1) {
                break;
            }
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
            delay += 100;
        }
    }

}