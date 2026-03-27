package ist.depchain.client;

import ist.depchain.common.utils.Config;
import java.util.Scanner;
import java.math.BigInteger;

public class ClientApp {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: mvn exec:java -Dexec.args=\"<configFile> <clientId>\"");
            System.out.println("Example: mvn exec:java -Dexec.args=\"../config/config-dev.json client1\"");
            return;
        }

        String configFile = args[0];
        String clientId = args[1];

        try {
            Config config = Config.loadConfiguration(configFile, clientId);
            ClientContext client = new ClientContext(config);
            MessageHandler messageHandler = new MessageHandler(client);
            ClientLibrary clientLib = new ClientLibrary(client, messageHandler);
            client.start();

            System.out.println("[INFO] Successfully started:");
            System.out.println("    - Client ID: " + clientId);
            System.out.println("    - Blockchain address: " + config.getProcessInfo(clientId).getAddress());

            Scanner in = new Scanner(System.in);
            try {
                while (true) {
                    System.out.println("\n=== DepChain Client ===");
                    System.out.println("0 - Check balance");
                    System.out.println("1 - Submit native transfer");
                    System.out.println("2 - ERC20 transfer");
                    System.out.println("3 - ERC20 increaseAllowance");
                    System.out.println("4 - ERC20 decreaseAllowance");
                    System.out.println("5 - ERC20 transferFrom");
                    System.out.println("exit - Quit");
                    System.out.print("> ");
                    String line = in.nextLine();

                    try {
                        switch (line) {
                            case "0": {
                                clientLib.submitBalanceCheck();
                                break;
                            }
                            case "1": {
                                System.out.print("Destination address (0x...): ");
                                String to = in.nextLine();

                                System.out.print("Value: ");
                                BigInteger value = new BigInteger(in.nextLine());

                                System.out.print("Gas price: ");
                                BigInteger gasPrice = new BigInteger(in.nextLine());

                                System.out.print("Gas limit: ");
                                BigInteger gasLimit = new BigInteger(in.nextLine());

                                clientLib.submitNativeTransfer(to, value, gasPrice, gasLimit);
                                break;
                            }

                            case "2": {

                                System.out.print("Token recipient address (0x...): ");
                                String to = in.nextLine();

                                System.out.print("Token amount (base units): ");
                                BigInteger amount = new BigInteger(in.nextLine());

                                System.out.print("Gas price: ");
                                BigInteger gasPrice = new BigInteger(in.nextLine());

                                System.out.print("Gas limit: ");
                                BigInteger gasLimit = new BigInteger(in.nextLine());

                                clientLib.submitTokenTransfer(to, amount, gasPrice, gasLimit);
                                break;
                            }

                            case "3": {

                                System.out.print("Spender address (0x...): ");
                                String spender = in.nextLine();

                                System.out.print("Increase amount (base units): ");
                                BigInteger amount = new BigInteger(in.nextLine());

                                System.out.print("Gas price: ");
                                BigInteger gasPrice = new BigInteger(in.nextLine());

                                System.out.print("Gas limit: ");
                                BigInteger gasLimit = new BigInteger(in.nextLine());

                                clientLib.submitIncreaseAllowance(spender, amount, gasPrice, gasLimit);
                                break;
                            }

                            case "4": {

                                System.out.print("Spender address (0x...): ");
                                String spender = in.nextLine();

                                System.out.print("Decrease amount (base units): ");
                                BigInteger amount = new BigInteger(in.nextLine());

                                System.out.print("Gas price: ");
                                BigInteger gasPrice = new BigInteger(in.nextLine());

                                System.out.print("Gas limit: ");
                                BigInteger gasLimit = new BigInteger(in.nextLine());

                                clientLib.submitDecreaseAllowance(spender, amount, gasPrice, gasLimit);
                                break;
                            }

                            case "5": {

                                System.out.print("From address (token owner): ");
                                String from = in.nextLine();

                                System.out.print("To address (recipient): ");
                                String to = in.nextLine();

                                System.out.print("Amount (base units): ");
                                BigInteger amount = new BigInteger(in.nextLine());

                                System.out.print("Gas price: ");
                                BigInteger gasPrice = new BigInteger(in.nextLine());

                                System.out.print("Gas limit: ");
                                BigInteger gasLimit = new BigInteger(in.nextLine());

                                clientLib.submitTransferFrom(from, to, amount, gasPrice, gasLimit);
                                break;
                            }

                            case "exit":
                                System.out.println("[INFO] Exiting...");
                                client.stop();
                                in.close();
                                return;

                            default:
                                System.out.println("Unknown option");
                        }
                    } catch (RuntimeException e) {
                        System.out.println("[ERROR] " + e.getMessage());
                    }
                }
            } finally {
                in.close();
            }
        } catch (Exception e) {
            System.out.println("[ERROR] Failed to start client: " + e.getMessage());
        }
    }
}