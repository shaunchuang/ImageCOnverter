package org.itri.ImageConverter;

import java.awt.image.BufferedImage;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import java.util.logging.Logger;
import java.util.logging.Level;

import org.apache.http.NoHttpResponseException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

public class ImageConverter {
	private static final Logger logger = Logger.getLogger(ImageConverter.class.getName());

	// 更新電子紙資訊
	public static void EPaperPost(BufferedImage image, String ipAddress) {

		// 檢查圖像尺寸
		if (image.getWidth() != 400 || image.getHeight() != 300) {
			logger.log(Level.INFO, "Image dimensions are not 400x300.");
			return;
		}

		// 轉換圖像
		int[][] binaryImage = convertToBinary(image);

		// 將二進制圖像轉換為字符串
		String result = convertImageToString(binaryImage);

		// 上傳結果給電子紙並更新
		logger.log(Level.INFO, "電子紙IP： " + ipAddress + " 資訊開始上傳");
		if (ipAddress.equals("192.168.225.203") || ipAddress.equalsIgnoreCase("192.168.225.204")
				|| ipAddress.equals("192.168.225.205")) {
			logger.log(Level.INFO, "Upload ESP32");
			uploadInBatchesESP32(ipAddress, result);
		} else {
			logger.log(Level.INFO, "Upload ESP8266");
			uploadInBatchesESP8266(ipAddress, result);
		}
	}

	public static int[][] convertToBinary(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		int[][] result = new int[height][width];

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int color = image.getRGB(x, y);
				int red = (color >> 16) & 0xff;
				int green = (color >> 8) & 0xff;
				int blue = color & 0xff;

				// 計算灰階度值
				int gray = (red + green + blue) / 3;

				// 設定二進制值
				result[y][x] = gray < 200 ? 0 : 1;
			}
		}

		return result;
	}

	// 將binary 資訊轉成String
	public static String convertImageToString(int[][] binaryImage) {
		StringBuilder sb = new StringBuilder();

		for (int y = 0; y < binaryImage.length; y++) {
			for (int x = 0; x < binaryImage[y].length; x += 8) {
				int value = 0;
				for (int i = 0; i < 8; i++) {
					value = (value << 1) | binaryImage[y][x + i];
				}
				sb.append(byteToStr(value));
			}
		}

		return sb.toString();
	}

	// 將byte轉換成String
	public static String byteToStr(int v) {
		char char1 = (char) ((v & 0xF) + 97);
		char char2 = (char) (((v >> 4) & 0xF) + 97);
		return new String(new char[] { char1, char2 });
	}

	private static void sendPostRequest(String requestUrl, String payload, String ipAddress, int retries, int timeout,
			boolean allowRetry) throws Exception {

		if (!isHostReachable(ipAddress, 5000, 3)) {
			logger.log(Level.INFO, "電子紙無法連線. 取消作業");
			throw new Exception(ipAddress + " 電子紙資訊上傳失敗");
		}

		if (retries == 0) {
			logger.log(Level.INFO, ipAddress + " ***連線失敗***已重試連線三次***");
			throw new Exception(ipAddress + " 電子紙資訊上傳失敗");
		}

		RequestConfig requestConfig = RequestConfig.custom().setSocketTimeout(timeout).setConnectTimeout(timeout)
				.build();
		try (CloseableHttpClient httpClient = HttpClients.custom().setDefaultRequestConfig(requestConfig).build();) {
//		try (CloseableHttpClient httpClient = HttpClients.createDefault();){

			logger.log(Level.INFO, "Create connection " + ipAddress);
			HttpPost httpPost = new HttpPost(requestUrl);
			httpPost.setEntity(new StringEntity(payload));
			try (CloseableHttpResponse response = httpClient.execute(httpPost);) {
				logger.log(Level.INFO, "E Paper info posted");
				int statusCode = response.getStatusLine().getStatusCode();
				if (statusCode == 200) {
					logger.log(Level.INFO, "Response is OK");
				} else {
					logger.log(Level.INFO, ipAddress + " Response is Bad. Status code: " + statusCode);
					if (allowRetry) {
						sendPostRequest(requestUrl, payload, ipAddress, retries - 1, 5000, allowRetry);
					}
				}
			}

		} catch (SocketTimeoutException e) {
			logger.log(Level.INFO, ipAddress + " Timeout!! 五秒內重試連線");
			try {
				Thread.sleep(2500);
				refreshURL(ipAddress);
				Thread.sleep(2500);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
			if (allowRetry) {
				sendPostRequest(requestUrl, payload, ipAddress, retries - 1, 5000, allowRetry);
			}

		} catch (NoHttpResponseException e) {
			logger.log(Level.INFO, "No response from server at: " + requestUrl);

		} catch (SocketException e) {
			logger.log(Level.INFO, ipAddress + " SocketException!! 五秒內重試連線");
			try {
				Thread.sleep(2500);
				refreshURL(ipAddress);
				Thread.sleep(2500);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
			if (allowRetry) {
				sendPostRequest(requestUrl, payload, ipAddress, retries - 1, 5000, allowRetry);
			}

		} catch (Exception e) {
			logger.log(Level.SEVERE, e.toString(), e);
			try {
				Thread.sleep(2500);
				refreshURL(ipAddress);
				Thread.sleep(2500);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
			if (allowRetry) {
				sendPostRequest(requestUrl, payload, ipAddress, retries - 1, 5000, allowRetry);
			}
		}
	}

	// 批次上傳資料 for ESP32
	public static void uploadInBatchesESP32(String ipAddress, String data) {
		int batchSize = 1000;
		String baseUrl = "http://" + ipAddress + "/";
		String startRequest = baseUrl + "EPDI_";
		String nextRequest = baseUrl + "NEXT_";
		String repeatedStr = "ppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppiodaLOAD_";
		String gapRequest = baseUrl + repeatedStr;
		String endRequest = baseUrl + "SHOW_";
		try {
			// 發送起始請求
			sendPostRequest(startRequest, "", ipAddress, 10, 45000, true);

			// 發送電子紙圖像資訊
			for (int i = 0; i < data.length(); i += batchSize) {

				String batch = data.substring(i, Math.min(i + batchSize, data.length()));

				String requestUrl = baseUrl + batch + "iodaLOAD_";

				sendPostRequest(requestUrl, "", ipAddress, 3, 5000, true);
			}

			// 發送NEXT訊號
			sendPostRequest(nextRequest, "", ipAddress, 3, 5000, true);

			// 發送GAP訊號
			for (int i = 0; i < 30; i++) {
				sendPostRequest(gapRequest, "", ipAddress, 3, 5000, true);
			}
			// 發送結束請求
			sendPostRequest(endRequest, "", ipAddress, 3, 30000, false);
			logger.log(Level.INFO, "電子紙IP： " + ipAddress + " 更新完成");
		} catch (Exception e) {
			logger.log(Level.SEVERE, e.toString(), e);
			return;
		}
	}

	// 批次上傳資料 ESP8266
	public static void uploadInBatchesESP8266(String ipAddress, String data) {
		int batchSize = 1500;
		String baseUrl = "http://" + ipAddress + "/";
		String repeatedStr = "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp";
		String startRequest = baseUrl + "EPD";
		String nextRequest = baseUrl + "NEXT";
		String endRequest = baseUrl + "SHOW";
		try {

			// 發送起始請求
			// 4.2b v2 為 "cc"，4.2 為 "na"
			if (isIPInRange(ipAddress, "192.168.225.100", "192.168.225.200")) {
				sendPostRequest(startRequest, "na", ipAddress, 10, 20000, true);
			} else if (isIPInRange(ipAddress, "192.168.225.200", "192.168.225.254")) {
				sendPostRequest(startRequest, "cc", ipAddress, 10, 20000, true);
			} else {
				logger.log(Level.INFO, "IP 不位於所在範圍內");
			}

			// 發送電子紙圖像資訊
			for (int i = 0; i < data.length(); i += batchSize) {

				String batch = data.substring(i, Math.min(i + batchSize, data.length()));

				String payload = batch + "mnfaLOAD";

				sendPostRequest(baseUrl + "LOAD", payload, ipAddress, 3, 5000, true);

			}

			// 發送NEXT訊號
			sendPostRequest(nextRequest, "", ipAddress, 3, 5000, true);

			// 發送GAP訊號
			for (int i = 0; i < 20; i++) {
				sendPostRequest(baseUrl + "LOAD", repeatedStr + "mnfaLOAD", ipAddress, 3, 5000, true);
			}

			// 發送結束請求
			sendPostRequest(endRequest, "", ipAddress, 3, 45000, false);
			logger.log(Level.INFO, "電子紙IP： " + ipAddress + " 更新完成");
		} catch (Exception e) {
			logger.log(Level.SEVERE, e.toString(), e);
			return;
		}
	}

	private static boolean isHostReachable(String ipAddress, int timeout, int retries) {

		for (int i = 0; i < retries; i++) {
			try {
				InetAddress address = InetAddress.getByName(ipAddress);
				if (address.isReachable(timeout)) {
					return true;
				} else {
					logger.log(Level.INFO, ipAddress + " 電子紙無法連線. 5秒後重試");
					Thread.sleep(2500);
					refreshURL(ipAddress);
					Thread.sleep(2500);
				}
			} catch (Exception e) {
				logger.log(Level.SEVERE, e.toString(), e);
				logger.log(Level.INFO, ipAddress + " 電子紙異常無法連線. 5秒後重試");
				try {
					Thread.sleep(2500);
					refreshURL(ipAddress);
					Thread.sleep(2500);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				}
			}
		}
		return false;
	}

	public static boolean isIPInRange(String ip, String startRange, String endRange) {
		try {
			long ipLong = ipToLong(InetAddress.getByName(ip));
			long startRangeLong = ipToLong(InetAddress.getByName(startRange));
			long endRangeLong = ipToLong(InetAddress.getByName(endRange));

			return ipLong >= startRangeLong && ipLong <= endRangeLong;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	public static long ipToLong(InetAddress ip) {
		byte[] octets = ip.getAddress();
		long result = 0;
		for (byte octet : octets) {
			result <<= 8;
			result |= octet & 0xff;
		}
		return result;
	}

	public static void refreshURL(String ipAddress) {
		String baseUrl = "http://" + ipAddress + "/";
		try {
			RequestConfig requestConfig = RequestConfig.custom().setSocketTimeout(5000).setConnectTimeout(5000).build();

			CloseableHttpClient httpClient = HttpClients.custom().setDefaultRequestConfig(requestConfig).build();

			HttpGet httpGet = new HttpGet(baseUrl);
			try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
				System.out.println(response.getStatusLine());
				logger.log(Level.INFO, baseUrl + " 更新網頁成功");
			}
		} catch (Exception e) {
			logger.log(Level.INFO, baseUrl + " 更新網頁失敗");
		}
	}

}
