package org.itri.ImageConverter;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

public class MultiTest {

	public static void main(String[] args) throws InterruptedException {
        try {
            // Parse command-line arguments
            String imagePath = null;
            for (int i = 0; i < args.length; i++) {
                if ("-g".equals(args[i])) {
                    imagePath = args[++i];
                }
            }

            if (imagePath == null) {
                System.err.println("Usage: java MultiTest -g <imagePath>");
                return;
            }

            BufferedImage image = ImageIO.read(new File(imagePath));

            List<String> ipAddresses = Files.readAllLines(Paths.get("D:\\ePaperTest\\ipAddress.txt"));
            // Repeat the program 30 times
            for (int i = 0; i < 30; i++) {
                ExecutorService executor = Executors.newFixedThreadPool(ipAddresses.size());
                for (String ipAddress : ipAddresses) {
                    executor.submit(() -> {
                        try {
                            ImageConverter.EPaperPost(image, ipAddress);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }

                executor.shutdown();
                executor.awaitTermination(1, TimeUnit.HOURS);
            }} catch (IOException e) {
            e.printStackTrace();
        }
	}
    
}
