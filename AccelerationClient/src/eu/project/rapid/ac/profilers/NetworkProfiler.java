/*******************************************************************************
 * Copyright (C) 2015, 2016 RAPID EU Project
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *******************************************************************************/
package eu.project.rapid.ac.profilers;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import eu.project.rapid.common.Configuration;
import eu.project.rapid.common.RapidMessages;

/**
 * Network information profiler
 * 
 */
// @TargetApi(8)
public class NetworkProfiler {
  private static final String TAG = "NetworkProfiler";

  private static final int rttInfinite = 100000000;
  private static final int rttPings = 5;
  public static int rtt = rttInfinite;

  // Keep the upload/download data rate history between the phone and the clone
  // Data rate in b/s
  private static final int bwWindowMaxLength = 20;
  private static List<NetworkBWRecord> ulRateHistory = new LinkedList<NetworkBWRecord>();
  private static List<NetworkBWRecord> dlRateHistory = new LinkedList<NetworkBWRecord>();
  public static NetworkBWRecord lastUlRate = null;
  public static NetworkBWRecord lastDlRate = null;

  public static String currentNetworkTypeName;
  public static String currentNetworkSubtypeName;
  private static byte[] buffer;
  private static final int BUFFER_SIZE = 10 * 1024;
  private static final int delayRefreshUlRate = 3 * 60 * 1000; // measure the rtt and rates every 30
                                                               // minutes
  private static final int delayRefreshDlRate = delayRefreshUlRate + 10000; // measure the rtt and
                                                                            // rates every 30
                                                                            // minutes
  // private static Handler uploadRateHandler;
  // private static Handler downloadRateHandler;
  // private static Runnable uploadRunnable;
  // private static Runnable downloadRunnable;

  private Context context;
  private static Configuration config;
  private NetworkInfo netInfo;
  private PhoneStateListener listener;
  private TelephonyManager telephonyManager;
  private ConnectivityManager connectivityManager;
  private WifiManager wifiManager;
  private BroadcastReceiver networkStateReceiver;

  private boolean stopReadingFiles;
  private ArrayList<Long> wifiTxPackets;
  private ArrayList<Long> wifiRxPackets;
  private ArrayList<Long> wifiTxBytes; // uplink data rate
  private ArrayList<Byte> threeGActiveState;
  public static final byte THREEG_IN_IDLE_STATE = 0;
  public static final byte THREEG_IN_FACH_STATE = 1;
  public static final byte THREEG_IN_DCH_STATE = 2;

  // For measuring the nr of bytes sent and received
  private final static int uid = android.os.Process.myUid();
  private Long startRxBytes;
  private Long startTxBytes;
  // Needed by Profiler
  public long rxBytes;
  public long txBytes;

  /**
   * Constructor used to create a network profiler instance during method execution
   */
  public NetworkProfiler() {
    stopReadingFiles = false;
    wifiTxPackets = new ArrayList<Long>();
    wifiRxPackets = new ArrayList<Long>();
    wifiTxBytes = new ArrayList<Long>();
    threeGActiveState = new ArrayList<Byte>();
  }

  /**
   * Constructor used to create the network profiler instance of the DFE
   * 
   * @param context
   */
  public NetworkProfiler(Context context, Configuration config) {

    this.context = context;
    NetworkProfiler.config = config;
    buffer = new byte[BUFFER_SIZE];

    telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    connectivityManager =
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

    /*
     * // FIXME: Crashes on Android 4+ due to networkonmainthread exception. // Fix this
     * implementing with alarm manager.
     * 
     * uploadRunnable = new UploadRateMeasurer(); uploadRateHandler = new Handler(); TimerTask
     * uploadRateTask = new TimerTask() {
     * 
     * @Override public void run() { uploadRateHandler.post(uploadRunnable); } }; Timer
     * uploadRateTimer = new Timer(); uploadRateTimer.schedule(uploadRateTask, delayRefreshUlRate,
     * delayRefreshUlRate);
     * 
     * downloadRunnable = new DownloadRateMeasurer(); downloadRateHandler = new Handler(); TimerTask
     * downloadRateTask = new TimerTask() {
     * 
     * @Override public void run() { downloadRateHandler.post(downloadRunnable); } }; Timer
     * downloadRateTimer = new Timer(); downloadRateTimer.schedule(downloadRateTask,
     * delayRefreshDlRate, delayRefreshDlRate);
     */
  }

  public static void addNewUlRateEstimate(long bytes, long nanoTime) {

    Log.d(TAG, "Sent " + bytes + " bytes in " + nanoTime + "ns");
    int ulRate = (int) ((((double) 8 * bytes) / nanoTime) * 1000000000);
    Log.i(TAG, "Estimated upload bandwidth: " + ulRate + " b/s (" + ulRate / 1000 + " Kbps)");

    // Rule 1: if the number of bytes sent was bigger than 10KB and the ulRate is small then keep
    // it, otherwise throw it
    // Rule 2: if the number of bytes sent was bigger than 50KB then keep the calculated ulRate
    if (bytes < 10 * 1000)
      return;
    else if (bytes < 50 * 1000 && ulRate > 250 * 1000)
      return;

    if (ulRateHistory.size() >= bwWindowMaxLength) {
      ulRateHistory.remove(0);
    }

    lastUlRate = new NetworkBWRecord(ulRate, System.currentTimeMillis());
    ulRateHistory.add(lastUlRate);

    // uploadRateHandler.removeCallbacks(uploadRunnable);
    // uploadRateHandler.postDelayed(uploadRunnable, delayRefreshUlRate);
  }

  public static void addNewDlRateEstimate(long bytes, long nanoTime) {

    Log.d(TAG, "Received " + bytes + " bytes in " + nanoTime + "ns");
    int dlRate = (int) ((((double) 8 * bytes) / nanoTime) * 1000000000);
    Log.i(TAG, "Estimated download bandwidth: " + dlRate + " b/s (" + dlRate / 1000 + " Kbps)");

    // Rule 1: if the number of bytes sent was bigger than 10KB and the ulRate is small then keep
    // it, otherwise throw it
    // Rule 2: if the number of bytes sent was bigger than 50KB then keep the calculated ulRate
    if (bytes < 10 * 1000)
      return;
    else if (bytes < 50 * 1000 && dlRate > 250 * 1000)
      return;

    if (dlRateHistory.size() >= bwWindowMaxLength)
      dlRateHistory.remove(0);

    lastDlRate = new NetworkBWRecord(dlRate, System.currentTimeMillis());
    dlRateHistory.add(lastDlRate);

    // downloadRateHandler.removeCallbacks(downloadRunnable);
    // downloadRateHandler.postDelayed(downloadRunnable, delayRefreshDlRate);
  }

  /**
   * Doing a few pings on a given connection to measure how big the RTT is between the client and
   * the remote machine
   * 
   * @param in
   * @param out
   * @return
   */
  public static int rttPing(InputStream in, OutputStream out) {
    Log.d(TAG, "Pinging");
    int tRtt = 0;
    int response;
    try {
      for (int i = 0; i < rttPings; i++) {
        Long start = System.nanoTime();
        Log.d(TAG, "Send Ping");
        out.write(eu.project.rapid.common.RapidMessages.PING);

        Log.d(TAG, "Read Response");
        response = in.read();
        if (response == RapidMessages.PONG)
          tRtt = (int) (tRtt + (System.nanoTime() - start) / 2);
        else {
          Log.d(TAG, "Bad Response to Ping - " + response);
          tRtt = rttInfinite;
        }

      }
      rtt = tRtt / rttPings;
      Log.d(TAG, "Ping - " + rtt / 1000000 + "ms");

    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
      tRtt = rttInfinite;
    }
    return rtt;
  }

  /**
   * Start counting transmitted data at a certain point for the current process (RX/TX bytes from
   * /sys/class/net/proc/uid_stat)
   */
  public void startTransmittedDataCounting() {

    startRxBytes = getProcessRxBytes();
    startTxBytes = getProcessTxBytes();

    // Used by the Energy model implemented for HTC Dream (G1) based on PowerTutor paper
    // No need to start the profilers on not supported phones
    // if (android.os.Build.MODEL.equals(RapidConstants.PHONE_NAME_HTC_G1)) {
    if (currentNetworkTypeName.equals("WIFI")) {
      calculateWifiRxTxPackets();
    } else {
      calculate3GStates();
    }
    // }
  }

  /**
   * Stop counting transmitted data and store it in the profiler object
   */
  public void stopAndCollectTransmittedData() {

    synchronized (this) {
      stopReadingFiles = true;
    }

    // Need this for energy estimation only when using Dream phone
    calculatePacketRate();
    calculateUplinkDataRate();

    rxBytes = getProcessRxBytes() - startRxBytes;
    txBytes = getProcessTxBytes() - startTxBytes;

    Log.d(TAG, "UID: " + uid + " RX bytes: " + rxBytes + " TX bytes: " + txBytes);
  }

  /**
   * @return RX bytes
   */
  public static Long getProcessRxBytes() {
    return TrafficStats.getUidRxBytes(uid);
  }

  /**
   * @return TX bytes
   */
  public static Long getProcessTxBytes() {
    return TrafficStats.getUidTxBytes(uid);
  }

  /**
   * @return Number of packets transmitted
   */
  public static Long getProcessTxPackets() {
    return TrafficStats.getUidTxPackets(uid);
  }

  /**
   * @return Number of packets received
   */
  public static Long getProcessRxPackets() {
    return TrafficStats.getUidRxPackets(uid);
  }

  /**
   * Intent based network state tracking - helps to monitor changing conditions without the
   * overheads of polling and only updating when needed (i.e. when something actually has changes)
   */
  public void registerNetworkStateTrackers() {
    networkStateReceiver = new BroadcastReceiver() {
      public void onReceive(Context context, Intent intent) {
        // context.unregisterReceiver(this);

        netInfo = connectivityManager.getActiveNetworkInfo();
        if (netInfo == null) {
          Log.d(TAG, "No Connectivity");
          currentNetworkTypeName = "";
          currentNetworkSubtypeName = "";
        } else {
          Log.d(TAG, "Connected to network type " + netInfo.getTypeName() + " subtype "
              + netInfo.getSubtypeName());
          currentNetworkTypeName = netInfo.getTypeName();
          currentNetworkSubtypeName = netInfo.getSubtypeName();
        }
      }
    };

    Log.d(TAG, "Register Connectivity State Tracker");
    IntentFilter networkStateFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
    context.registerReceiver(networkStateReceiver, networkStateFilter);

    listener = new PhoneStateListener() {
      @Override
      public void onDataConnectionStateChanged(int state, int networkType) {
        if (state == TelephonyManager.DATA_CONNECTED) {
          if (networkType == TelephonyManager.NETWORK_TYPE_EDGE)
            Log.d(TAG, "Connected to EDGE network");
          else if (networkType == TelephonyManager.NETWORK_TYPE_GPRS)
            Log.d(TAG, "Connected to GPRS network");
          else if (networkType == TelephonyManager.NETWORK_TYPE_UMTS)
            Log.d(TAG, "Connected to UMTS network");
          else
            Log.d(TAG, "Connected to other network - " + networkType);
        } else if (state == TelephonyManager.DATA_DISCONNECTED) {
          Log.d(TAG, "Data connection lost");
        } else if (state == TelephonyManager.DATA_SUSPENDED) {
          Log.d(TAG, "Data connection suspended");
        }

      }
    };

    Log.d(TAG, "Register Telephony Data Connection State Tracker");
    telephonyManager.listen(listener, PhoneStateListener.LISTEN_DATA_CONNECTION_STATE);
  }

  /**
   * Class to be used for measuring the data rate and RTT every 30 minutes.
   *
   */
  private class UploadRateMeasurer implements Runnable {
    private static final String TAG = "UploadRateMeasurer";

    @Override
    public void run() {
      Log.i(TAG, "Measuring the upload rate");
      NetworkProfiler.measureUlRate();
      // uploadRateHandler.postDelayed(this, delayRefreshUlRate);
    }
  }

  /**
   * Class to be used for measuring the data rate and RTT every 30 minutes.
   *
   */
  private class DownloadRateMeasurer implements Runnable {
    private static final String TAG = "DownloadRateMeasurer";

    @Override
    public void run() {
      Log.i(TAG, "Measuring the download rate");
      NetworkProfiler.measureDlRate();
      // downloadRateHandler.postDelayed(this, delayRefreshDlRate);
    }
  }

  /**
   * Get the number of packets Tx and Rx every second and update the arrays.<br>
   */
  private void calculateWifiRxTxPackets() {
    Thread t = new Thread() {
      public void run() {

        while (!stopReadingFiles) {

          wifiRxPackets.add(NetworkProfiler.getProcessRxPackets());
          wifiTxPackets.add(NetworkProfiler.getProcessTxPackets());
          wifiTxBytes.add(NetworkProfiler.getProcessTxBytes());

          try {
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
        }
      }
    };
    t.start();
  }

  private void calculatePacketRate() {
    for (int i = 0; i < wifiRxPackets.size() - 1; i++)
      wifiRxPackets.set(i, wifiRxPackets.get(i + 1) - wifiRxPackets.get(i));

    for (int i = 0; i < wifiTxPackets.size() - 1; i++)
      wifiTxPackets.set(i, wifiTxPackets.get(i + 1) - wifiTxPackets.get(i));
  }

  private void calculateUplinkDataRate() {
    for (int i = 0; i < wifiTxBytes.size() - 1; i++) {
      wifiTxBytes.set(i, wifiTxBytes.get(i + 1) - wifiTxBytes.get(i));
    }
  }


  byte timeoutDchFach = 6; // Inactivity timer for transition from DCH -> FACH
  byte timeoutFachIdle = 4; // Inactivity timer for transition from FACH -> IDLE
  int uplinkThreshold = 151;
  int downlikThreshold = 119;
  byte threegState = THREEG_IN_IDLE_STATE;
  boolean fromIdleState = true;
  boolean fromDchState = false;
  private long prevRxBytes, prevTxBytes;

  private void calculate3GStates() {
    Thread t = new Thread() {
      public void run() {

        while (!stopReadingFiles) {

          switch (threegState) {
            case THREEG_IN_IDLE_STATE:
              threegIdleState();
              break;
            case THREEG_IN_FACH_STATE:
              threegFachState();
              break;
            case THREEG_IN_DCH_STATE:
              threegDchState();
              break;
          }

          try {

            Thread.sleep(1000);

          } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
        }
      }
    };
    t.start();
  }

  private void threegIdleState() {
    int dataActivity = telephonyManager.getDataActivity();

    if (dataActivity == TelephonyManager.DATA_ACTIVITY_IN
        || dataActivity == TelephonyManager.DATA_ACTIVITY_OUT
        || dataActivity == TelephonyManager.DATA_ACTIVITY_INOUT) {
      // 3G is in the FACH state because is sending or receiving data
      Log.d(TAG, "3G in FACH state from IDLE");
      threegState = THREEG_IN_FACH_STATE;
      fromIdleState = true;
      threegFachState();
      return;
    }

    Log.d(TAG, "3G in IDLE state");
    // 3G is in the IDLE state
    threeGActiveState.add(THREEG_IN_IDLE_STATE);
  }

  private void threegFachState() {
    if (fromIdleState || fromDchState) {
      // The FACH state is just entered from IDLE or DCH, we should stay here at least 1 second
      // to measure the size of the buffer and in case to transit in DCH in the next second
      fromIdleState = false;
      fromDchState = false;
      prevRxBytes = NetworkProfiler.getProcessRxBytes();
      prevTxBytes = NetworkProfiler.getProcessTxBytes();
    } else { // 3G was in FACH
      if (timeoutFachIdle == 0) {
        Log.d(TAG, "3G in IDLE state from FACH");
        timeoutFachIdle = 4;
        threegState = THREEG_IN_IDLE_STATE;
        threegIdleState();
        return;
      } else if (telephonyManager.getDataActivity() == TelephonyManager.DATA_ACTIVITY_NONE) {
        Log.d(TAG, "3G in FACH state with no data activity");
        timeoutFachIdle--;
      } else
        timeoutFachIdle = 4;

      if ((NetworkProfiler.getProcessRxBytes() - prevRxBytes) > downlikThreshold
          || (NetworkProfiler.getProcessTxBytes() - prevTxBytes) > uplinkThreshold) {
        Log.d(TAG, "3G in DCH state from FACH");
        timeoutFachIdle = 4;
        threegState = THREEG_IN_DCH_STATE;
        threegDchState();
        return;
      }
    }

    Log.d(TAG, "3G in FACH state");
    threeGActiveState.add(THREEG_IN_FACH_STATE);
  }

  private void threegDchState() {
    if (timeoutDchFach == 0) {
      Log.d(TAG, "3G in FACH state from DCH");
      timeoutDchFach = 6;
      threegState = THREEG_IN_FACH_STATE;
      fromDchState = true;
      threegFachState();
      return;
    } else if (telephonyManager.getDataActivity() == TelephonyManager.DATA_ACTIVITY_NONE) {
      Log.d(TAG, "3G in DCH state with no data activity");
      timeoutDchFach--;
    } else
      timeoutDchFach = 6;

    Log.d(TAG, "3G in DCH state");
    threeGActiveState.add(THREEG_IN_DCH_STATE);
  }

  public int getWiFiRxPacketRate(int i) {
    return wifiRxPackets.get(i).intValue();
  }

  public int getWiFiTxPacketRate(int i) {
    return wifiTxPackets.get(i).intValue();
  }

  public boolean noConnectivity() {
    return (connectivityManager.getActiveNetworkInfo()) == null;
  }

  public int getLinkSpeed() {
    WifiInfo wifiInfo = wifiManager.getConnectionInfo();
    return wifiInfo.getLinkSpeed();
  }

  public byte get3GActiveState(int i) {
    return threeGActiveState.get(i);
  }

  public long getUplinkDataRate(int i) {
    return wifiTxBytes.get(i);
  }

  /**
   * Someone sets the data rate manually (maybe for testing)
   * 
   * @param dataRate
   */
  public void setDataRate(int dataRate) {
    lastDlRate = new NetworkBWRecord(dataRate, System.currentTimeMillis());
    dlRateHistory.add(lastDlRate);

    lastUlRate = new NetworkBWRecord(dataRate, System.currentTimeMillis());
    ulRateHistory.add(lastUlRate);
  }

  public void onDestroy() {
    this.context.unregisterReceiver(networkStateReceiver);

    // if (uploadRateHandler != null) {
    // uploadRateHandler.removeCallbacks(uploadRunnable);
    // }
    //
    // if (downloadRateHandler != null) {
    // downloadRateHandler.removeCallbacks(downloadRunnable);
    // }
  }

  public static NetworkBWRecord measureDlRate() {

    OutputStream os = null;
    InputStream is = null;
    DataInputStream dis = null;

    long time = 0;
    long rxBytes = 0;

    try {
      final Socket clientSocket =
          new Socket(config.getClone().getIp(), config.getClonePortBandwidthTest());
      os = clientSocket.getOutputStream();
      is = clientSocket.getInputStream();
      dis = new DataInputStream(is);

      os.write(RapidMessages.DOWNLOAD_FILE);

      new Thread(new Runnable() {

        @Override
        public void run() {
          try {
            Thread.sleep(3000);
          } catch (InterruptedException e1) {
          } finally {
            try {
              clientSocket.close();
            } catch (IOException e) {
            }
          }
        }
      }).start();

      time = System.nanoTime();
      rxBytes = NetworkProfiler.getProcessRxBytes();
      while (true) {
        is.read(buffer);
        os.write(1);
      }

    } catch (UnknownHostException e) {
    } catch (SocketException e) {
    } catch (IOException e) {
    } finally {

      time = System.nanoTime() - time;
      rxBytes = NetworkProfiler.getProcessRxBytes() - rxBytes;

      if (os != null) {

        // If the streams are null it means that no measurement was performed
        addNewDlRateEstimate(rxBytes, time);

        try {
          os.close();
          is.close();
          dis.close();
        } catch (IOException e) {
          Log.w("", "Could not close streams");
        }
      }
    }
    return lastDlRate;
  }

  public static NetworkBWRecord measureUlRate() {

    OutputStream os = null;
    InputStream is = null;
    DataInputStream dis = null;

    long txTime = 0;
    long txBytes = 0;

    try {
      final Socket clientSocket =
          new Socket(config.getClone().getIp(), config.getClonePortBandwidthTest());
      os = clientSocket.getOutputStream();
      is = clientSocket.getInputStream();
      dis = new DataInputStream(is);

      os.write(RapidMessages.UPLOAD_FILE);

      while (true) {
        os.write(buffer);
        is.read();
      }

    } catch (UnknownHostException e) {
    } catch (SocketException e) {
      Log.i(TAG, "");
    } catch (IOException e) {
    } finally {

      if (os != null) {
        try {
          os.close();
          is.close();
          dis.close();
        } catch (IOException e) {
          Log.w("", "Could not close streams");
        }
      }

      Socket clientSocket = null;
      try {
        clientSocket = new Socket(config.getClone().getIp(), config.getClonePortBandwidthTest());
        os = clientSocket.getOutputStream();
        is = clientSocket.getInputStream();
        dis = new DataInputStream(is);

        os.write(RapidMessages.UPLOAD_FILE_RESULT);
        txBytes = dis.readLong();
        txTime = dis.readLong();

        addNewUlRateEstimate(txBytes, txTime);

      } catch (UnknownHostException e) {
      } catch (IOException e) {
      } catch (Exception e) {
      } finally {
        if (os != null) {
          try {
            os.close();
            is.close();
            dis.close();
            clientSocket.close();
          } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
        }
      }
    }
    return lastUlRate;
  }
}