package com.solace;

import javax.swing.*;
//import com.seaglasslookandfeel.*;

import java.awt.EventQueue;
import java.awt.Font;

import javax.swing.SwingWorker.StateValue;

import java.awt.event.ActionListener;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.SystemColor;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import com.solacesystems.pubsub.sdkperf.RemoteSDKP;

import net.miginfocom.swing.MigLayout;

import javax.swing.border.EtchedBorder;
import java.awt.Component;

public class SDKPerfGUIApp {

	/**
	 * @author David Wray, Solace Systems
	 *
	 */
	private static Dimension defaultSize;
	private static Dimension minimumSize = new Dimension(750,340);
	private static Dimension initialSize = new Dimension(750,640);
	private JFrame frmSdkperfGui;
	private JTextField Destination;
	private JTextField Address;
	private int publishersCount = 0;
	private int subscribersCount = 0;
	private HashMap<String,JTextArea> ClientTextAreas = new HashMap<String, JTextArea>();
	private HashMap<String,JTextField> OverrideDestinations = new HashMap<String, JTextField>();
	private HashMap<String,JComboBox<String>> OverrideCoSLevel = new HashMap<String, JComboBox<String>>();
	private HashMap<String,JCheckBox> OverrideDA = new HashMap<String, JCheckBox>();
	private HashMap<String,JRadioButton> OverrideTQs = new HashMap<String, JRadioButton>();
	private HashMap<String,JRadioButton> OverrideBrowser = new HashMap<String, JRadioButton>();
	private HashMap<String,SwingWorker<Void,Void>> ClientWorkers = new HashMap<String, SwingWorker<Void, Void>>();
	private HashMap<String, SDKPerfControl> sdkPerfControls = new HashMap<String, SDKPerfControl>();
	private JTabbedPane tabbedPanePublishers;
	private JTabbedPane tabbedPaneSubscribers;

	// Hit max no swing threads with all the updating controls and tab panes so need my own bigger thread pool 
	private int nSwingWorkerThreads = 30; 
	private ExecutorService threadPool = Executors.newFixedThreadPool(nSwingWorkerThreads);
	//starting to refactor so Clients can be dynamically added and to merge Publisher/Subscriber specific methods	
	// Step 1: add tabbed pane for output, add collection for workers and multiple text areas - done
	//
	public void showClientTextStream(Process sDKPerfProcess, Method ConnectedCallback, Method ProgressCallback, SDKPerfControl sdkPerfControl, String ClientName, boolean bPublisher, String builtCommand) {
		// get client worker from client worker collection
		SwingWorker<Void,Void> Worker = null;
		
		if (ClientWorkers.containsKey(ClientName)) {
			//stop updates from previous stream
			Worker = ClientWorkers.get(ClientName);
			Worker.cancel(true);
			ClientWorkers.remove(ClientName);
		} 
				
		Worker = new SwingWorker<Void,Void>() {
			public Void doInBackground() {
				String LF = System.getProperty("line.separator");
				//currently text areas hardcoded and added manually and have associated other objects not checking if they are added
				
				JTabbedPane tabbedPane = null;
				
				// unfortunately  publisher and subscriber do not have the same number of letters
				// work out client number and switch tabs
				int ClientNumber= setOutputDisplayTab(ClientName, bPublisher);
				
				if (bPublisher) {
					tabbedPane = tabbedPanePublishers;
				} else {
					tabbedPane = tabbedPaneSubscribers;
				}
				
				final JTextArea textArea = ClientTextAreas.get(ClientName);
			    // reset the text area
				textArea.setText("Running sdkperf as: "+LF+builtCommand+LF+LF);
				
				JScrollPane scrollPaneClient = (JScrollPane) tabbedPane.getComponentAt(ClientNumber);
				try{
					InputStreamReader isr = new InputStreamReader(sDKPerfProcess.getInputStream());
				    BufferedReader ClientReader = new BufferedReader(isr);
					String line = null;
					long FinalProgress=0;
					long FinalPairedProgress = 0;
					try {
						// fetch pub/sub counts with RMI
						Registry registry = null;
						// set up timer for message counter updates
						Timer counterTimer = null;
						
						// set up auto termination timer if necessary
						Timer terminationTimer = null;
							
						//begin loop to read output stream
						boolean bConnected = false;
						boolean bTerminating = false;
						boolean bExceptionFound = false;
						while ((line = ClientReader.readLine()) != null && !isCancelled()){
							// weirdly, it's possible for a class not found exception to not terminate the process so we need to check for and deal with this
							if (line.contains("Exception in thread") || line.contains("Queue Not Found")) {
								bExceptionFound = true;
								textArea.append("Exception detected while executing sdkPerf: terminating");
								sdkPerfControl.StopProcess();
							}
							textArea.append(line+LF);	
							
							// connect RMI when we get the string "Getting ready to start clients" in the output stream
							if (line.contains("Getting ready to start clients") && !bConnected && !bTerminating) {
								bConnected = true;
								//invoke connected method
								ConnectedCallback.invoke(sdkPerfControl);
								//connect RMI
								try {
									registry = LocateRegistry.getRegistry(sdkPerfControl.getRMIRegistryPort());
									final RemoteSDKP stub = (RemoteSDKP) registry.lookup("RemoteSDKP"+ClientName);
									//Launch timer
									ActionListener listenerCounter = new ActionListener(){
										public void actionPerformed(ActionEvent event){
											// fetch counts using RMI
											long Progress = 0;
											long PairedProgress = 0;
											try {
												if (bPublisher) {
													Progress = stub.getPubCount();
												} else {
													Progress = stub.getSubCount();
												}
												if (isSingleClientMode()) {
													PairedProgress = stub.getSubCount();
												}
												ProgressCallback.invoke(sdkPerfControl, Progress, PairedProgress);
											} catch (java.rmi.UnmarshalException ignore) {}
											catch (java.rmi.ConnectException ignore) {} 
											catch (RemoteException e) {
												e.printStackTrace();
											} catch (IllegalAccessException e) {
												e.printStackTrace();
											} catch (IllegalArgumentException e) {
												e.printStackTrace();
											} catch (InvocationTargetException e) {
												e.printStackTrace();
											}
										}
									};
									counterTimer = new Timer(500, listenerCounter);
									counterTimer.start();
									// start a termination timer if running in timed mode
									if (chckbxEnableTimer.isSelected()) {
										ActionListener listenerTermination = new ActionListener(){
											public void actionPerformed(ActionEvent event){
												// Terminate client using RMI
												try {
														stub.doRemoteShutdown();
														textArea.append(LF+"==>Client Terminated by Timer"+LF);
												} catch (java.rmi.UnmarshalException ignore) {}
												catch (java.rmi.ConnectException ignore) {} 
												catch (RemoteException e) {
													e.printStackTrace();
												} catch (IllegalArgumentException e) {
													e.printStackTrace();
												}
											}
										};
										terminationTimer =  new Timer((int)getTimerDuration(), listenerTermination);
										terminationTimer.start();
									}
								} catch (RemoteException e) {
									e.printStackTrace();
								} catch (NotBoundException e) {
									e.printStackTrace();
								} 
							}
							
							if (line.contains("Pausing -ped time to allow clients to finish recv")) {
								bTerminating = true;
								bConnected = false;
							}
							// final double check on output numbers
							// this is mostly redundant now we have the timer polling for updates but there is
							// a chance a client could terminate before we get the final total, so I'm leaving this in
							if (bTerminating) {
								if (bPublisher) {
									if (line.contains("Total Messages transmitted")) {
										String[] finalPublish = line.split("=");
										FinalProgress = Long.parseLong(finalPublish[1].trim());
									}
								} else {
									if (line.contains("Total Messages received across")) {
										String[] finalRcv = line.split("=");
										FinalProgress = Long.parseLong(finalRcv[1].trim());
									}
								}
								if (isSingleClientMode()) {
									if (line.contains("Total Messages received across")) {
										String[] finalRcv = line.split("=");
										FinalPairedProgress = Long.parseLong(finalRcv[1].trim());
									}
								}
								ProgressCallback.invoke(sdkPerfControl, FinalProgress, FinalPairedProgress);
							}
						}
						
						// stop updates
						if (counterTimer != null) {
							counterTimer.stop();
						}
						ClientReader.close();
						isr.close();
					} catch (IOException e) {
						System.out.println("I/O Exception in background thread, probably due to forced termination of SDK Perf Client");
						if (false) {
							e.printStackTrace();
						}
					}
				}catch(Exception e1){
					System.out.println("Exception in SDKPerfSwingWorker");
					e1.printStackTrace();
				}
				return null;
			}};

			threadPool.submit(Worker);
			ClientWorkers.put(ClientName, Worker);
	}

	public boolean isMQTT() {
		return chckbxEnableMqtt.isSelected();
	}
	public boolean isMQTT5() {
		return rdbtnMQTTv5.isSelected();
	}

	public boolean isMQTTWill() {
		return chckbxMqttWill.isSelected();
	}
	
	public boolean isREST() {
		return chckbxEnableRest.isSelected();
	}
	
	public boolean isProvisionNonExclusive() {
		if (chckbxNonExclusive.isSelected()) {
			return true;
		} else {
			return false;
		}
	}
	
	public String getRouterAddress() {
		return Address.getText();
	}

	public String getMessageDestination(String ClientName) {
		// more complicated now with overrides
		String DestinationResult = Destination.getText();
		if (chckbxEnablePerClient.isSelected()) {
			DestinationResult = OverrideDestinations.get(ClientName).getText();
			//add deliver always override if selected (only for subscribers)
			if (OverrideDA.containsKey(ClientName) && OverrideDA.get(ClientName).isSelected()) {
				DestinationResult = "\""+DestinationResult+"<TOPIC_END/>DA=1\"";
			}
		}
		return DestinationResult;
	}
	
	public String getCoSLevel(String ClientName) {
		return (String) OverrideCoSLevel.get(ClientName).getSelectedItem();
	}

	private String RouterAddress="localhost:55554";
	private String MessageDestination="topic/TopicDemo";
	
	private boolean isTopic = true;
	
	public boolean isTopic(String ClientName) {
		boolean TopicResult = isTopic;
		
		if (isMQTT()) {
			// it seems pql is not valid for MQTT, you need to use ptl and set topic or queue with the QoS setting, so isTopic always returns true for MQTT
			return true;
		}
		// return topic/queue for overrides if necessary.  Still works now there are 3 choices because we 
		// still need to add the queue flag if we are browsing a queue
		if (chckbxEnablePerClient.isSelected()) {
			TopicResult = OverrideTQs.get(ClientName).isSelected();
		}
		return TopicResult;
	}
	public boolean isBrowser(String ClientName) {
		return OverrideBrowser.get(ClientName).isSelected();
	}
	
	public boolean isQuiet(boolean bPublisher) {
		if (bPublisher) {
			return chckbxQuietPub.isSelected();
		} else {
			return chckbxQuietSub.isSelected();
		}
	}

	public String getVPN() {
		return VPN.getText();
	}

	public String getUser() {
		return User.getText();
	}

	private JTextField VPN;
	private JTextField User;
	private JPasswordField txtPassword;
	private final ButtonGroup btngrpTopicQueue = new ButtonGroup();

	public String getFastPublishSpeed() {
		return txtFastPublishSpeed.getText();
	}

	public String getSlowPublishSpeed() {
		return txtSlowPublishSpeed.getText();
	}

	public String getFastSubscriberDelay() {
		return txtFastSubscriberDelay.getText();
	}

	public String getSlowSubscriberDelay() {
		return txtSlowSubscriberDelay.getText();
	}

	public String getMessageCount(boolean fast) {
		if (chckbxEnableTimer.isSelected()) {
			long timerDuration = getTimerDuration();
			int speed=0;
			if (fast) {
			   speed = Integer.parseInt(getFastPublishSpeed());
			} else {
				speed = Integer.parseInt(getSlowPublishSpeed());
			}
			// make sure message count is at least large enough for publish rate * seconds to run (I am doubling it)
			// could be an option on publishers to give exact message counts?
			long count = speed * (timerDuration/1000) *2;
			return Long.toString(count);
		} else {
			return txtMessageCount.getText();
		}
	}

	private long getTimerDuration() {
		Date time = (Date)timeSpinner.getValue();
		long timeDifference = time.getTime() - baseDate.getTime();
		return timeDifference;
	}

	public String getLatencyGranularity() {
		return txtLatencyGranularity.getText();
	}

	public String getLatencyBuckets() {return String.valueOf(cmbobxLatencyBuckets.getSelectedItem());}

	public boolean isEnableLatencyMeasurement() {
		return chckbxEnableLatencyMeasurement.isSelected();
	}

	public boolean isSingleClientMode() {
		return chckbxEnableSingleClient.isSelected();
	}

	public boolean isPrintLatencyStats() {
		return chckbxPrintStats.isSelected();
	}

	public boolean isOrderCheck() {
		return chckbxOrderCheck.isSelected();
	}
	public boolean isTLS() {
		return chckbxEnableTLS.isSelected();
	}

	public boolean isDTO(){
		return chckbxDeliverToOne.isSelected();
	}
	
	public boolean isRequestReply() {
		return chckbxRequestreply.isSelected();
	}
	
	public boolean isReplyMode() {
		return chckbxReplyMode.isSelected();
	}

	private JTextField txtFastPublishSpeed;
	private JTextField txtSlowPublishSpeed;
	private JTextField txtFastSubscriberDelay;
	private JTextField txtSlowSubscriberDelay;
	private JTextField txtMessageCount;
	private JLabel lblLatencyGranularity;
	private JTextField txtLatencyGranularity;
	private JLabel lblLatencyBuckets;
	private String[] latencyBucketValues = new String[]{"1024","2048","3072","4096"};
	private JComboBox cmbobxLatencyBuckets;
	private JCheckBox chckbxEnableLatencyMeasurement;
	private JCheckBox chckbxPrintStats;
	private JCheckBox chckbxOrderCheck;
	private JCheckBox chckbxEnableTLS;
	private JCheckBox chckbxEnableTimer;
	private JSpinner timeSpinner;
	private Date baseDate;
	private JCheckBox chckbxEnableSingleClient;
	private JCheckBox chckbxDeliverToOne;
	private JCheckBox chckbxRequestreply;
	private JCheckBox chckbxReplyMode;
	private JTextField textFieldDestinationP1;
	private JTextField textFieldDestinationP2;
	private JTextField textFieldDestinationP3;
	private JTextField textFieldDestinationS1;
	private JTextField textFieldDestinationS2;
	private JTextField textFieldDestinationS3;
	private final ButtonGroup buttonGroupTQP1 = new ButtonGroup();
	private final ButtonGroup buttonGroupTQP2 = new ButtonGroup();
	private final ButtonGroup buttonGroupTQP3 = new ButtonGroup();
	private final ButtonGroup buttonGroupTQS1 = new ButtonGroup();
	private final ButtonGroup buttonGroupTQS2 = new ButtonGroup();
	private final ButtonGroup buttonGroupTQS3 = new ButtonGroup();
	private JTabbedPane tpDestinationOverrides;
	private JCheckBox chckbxEnablePerClient;
	private JRadioButton rdbtnTopicP3;
	private JRadioButton rdbtnTopicP2;
	private JRadioButton rdbtnTopicP1;
	private JRadioButton rdbtnTopicS3;
	private JRadioButton rdbtnTopicS2;
	private JRadioButton rdbtnTopicS1;
	private JCheckBox chckbxQuietSub;
	private JCheckBox chckbxQuietPub;
	private JCheckBox chckbxProvisionEndpoints;
	private JCheckBox chckbxNonExclusive;
	private SDKPerfControl Subscriber3;
	private SDKPerfControl Publisher3;
	private SDKPerfControl Subscriber2;
	private SDKPerfControl Publisher2;
	private SDKPerfControl Subscriber1;
	private SDKPerfControl Publisher1;
	// these labels need to be available outside their creation method as they get changed by the single client mode button
	private JLabel lblSubscribers;
	private JLabel lblPublishers;
	private JRadioButton rdbtnBrowserS3;
	private JRadioButton rdbtnBrowserS2;
	private JRadioButton rdbtnBrowserS1;
	private JCheckBox chckbxEnableMqtt;
	private JCheckBox chckbxCleanSession;
	private JComboBox<String> comboBoxMQTTWillQoS;
	private final ButtonGroup rdbtnMQTTVer = new ButtonGroup();
	private JRadioButton rdbtnMQTTv3;
	private JRadioButton rdbtnMQTTv5;
	private JTextField txtTopicmqttwill;
	private JTextField textFieldWillMessageSize;
	private JCheckBox chckbxRetainWillMessage;
	private JCheckBox chckbxMqttWill;
	private JCheckBox chckbxEnableRest;
	private JTextField textFieldRESTSvrPortList;
	private JComboBox<String> comboBoxRESTClientMode;
	private JTextField textFieldRESTRRWaitTime;
	private JTextField textFieldRESTLocalIPList;
	private JLabel lblMqttPort;
	private JLabel lblMqttClientVer;
	private JTextField textFieldMQTTPort;
	private JLabel lblRestPort;
	private JTextField textFieldRESTPort;
	private JCheckBox chckbxXmlAttachments;
	private JCheckBox chckbxCallbackOnReactor;
	private JSplitPane splitPane;
	private int BaseRMIPort=10800;
	private boolean baseRMIPortChecked = false;
	private JCheckBox chckbxDeliverAlwaysS1;
	private JCheckBox chckbxDeliverAlwaysS2;
	private JCheckBox chckbxDeliverAlwaysS3;
	private JLabel lblFont;
	// Text area font stuff
	private static String[] fontOptions = {"Serif", "Agency FB", "Arial", "Calibri", "Cambrian", "Century Gothic", "Comic Sans MS", "Courier New", "Forte", "Garamond", "Monospaced", "Segoe UI", "Times New Roman", "Trebuchet MS", "Serif"};
	private static Integer[] sizeOptions = {8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28};
	private JComboBox<String> comboBoxFont;
	private JComboBox<Integer> comboBoxFontSize;
	private Font currentFont;
	private Component rigidArea;
	private JLabel lblCosLevel;
	private JComboBox<String> cmbxCoSP1;
	private JComboBox<String> cmbxCoSP2;
	private JComboBox<String> cmbxCoSP3;
	private String[] cosList = {"0", "1", "2","3"};
	private AdjustmentListener messagesAdjustmentListener;
	private JCheckBox chckbxShowReply;
	private JLabel lblRetryCount;
	private JTextField txtRetryCount;
	
	public boolean isBaseRMIPortChecked() {
		return baseRMIPortChecked;
	}
	
	public void updateBaseRMIPort(int newPort) {
		BaseRMIPort = newPort;
		baseRMIPortChecked = true;
	}
	
	public int getCheckedBaseRMIPort() {
		return BaseRMIPort;
	}

	
	public boolean isProvisionEndpoints() {
		return chckbxProvisionEndpoints.isSelected();
	}

	public String getPassword(){
		return new String(txtPassword.getPassword());
	}

	public static void infoBox(String infoMessage, String titleBar)
	{
		JOptionPane.showMessageDialog(null, infoMessage, "InfoBox: " + titleBar, JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {
		EventQueue.invokeLater(new Runnable() {

			public void run() {
				try {
					SDKPerfGUIApp window = new SDKPerfGUIApp();
					try {
					    // Significantly improves the look of the output in each OS..
					    // By making it look 'just like' all the other apps.
					    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
						// Use seaglass look and feel - would be nice but menu doesn't work in seaglass for some reason					
//					    UIManager.setLookAndFeel("com.seaglasslookandfeel.SeaGlassLookAndFeel");
					} catch(Exception weTried) {}

					window.frmSdkperfGui.setMinimumSize(minimumSize);
					window.frmSdkperfGui.setSize(initialSize);
//					window.frmSdkperfGui.pack();
					window.frmSdkperfGui.setLocationRelativeTo(null);
					window.frmSdkperfGui.setResizable(true);
					window.frmSdkperfGui.setVisible(true);

					defaultSize = window.frmSdkperfGui.getSize();
					// once we have set a default size with the output windows at their defaults, allow them to be resized to nothing
					window.tabbedPaneSubscribers.setMinimumSize(new Dimension(0,0));
					window.tabbedPanePublishers.setMinimumSize(new Dimension(0,0));
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	/**
	 * Create the application.
	 */
	public SDKPerfGUIApp() {
		// I totally forgot when I decided on using RMI to get stats from sdkperf that if someone started multiple clients the port numbers would clash
		// so, we need to check if one or more sdkperf instances are already running and adjust the base RMI port accordingly.
		setBaseRMIPort();
		initialize();
	}

	private void setBaseRMIPort() {
		// get list of running tasks, obviously different if windows or linux
		String OS = System.getProperty("os.name");
		String taskCommand;
		if (OS.toLowerCase().contains("windows")) {
			taskCommand = System.getenv("windir") +"\\system32\\"+"tasklist.exe /v";
		} else {
			taskCommand = "ps -e";
		}
		
		int count = -1;
		try {
		    String line;
		    Process p = Runtime.getRuntime().exec(taskCommand);
		    BufferedReader input = new BufferedReader(new InputStreamReader(p.getInputStream()));
		    while ((line = input.readLine()) != null) {
	        	// count the jars but not the sdkperfs themselves
		        if (line.contains("SDKPerfGUI") && !line.contains("Dcom.sun.management.jmxremote.ssl")) {
		        	count++;
		        }
		    }
		    input.close();
//		    System.out.println("There are "+count+" additional copies of SDKPerfGUI running");
		    // set base port to be 10xcount - allow 10 ports per app
		    BaseRMIPort += 10*count;
//		    System.out.println("Set Base RMI Port to: "+BaseRMIPort);
		    // unfortunately this technique isn't foolproof.  If someone, for example, 
		    // starts copies 1,2,3&4, then closes 1&2 and starts 5, 5 will think it is copy 3 and there will be a port clash
		    // we also can't start probing ports at this stage, as someone might have started 5 copies but not started any clients yet.
		} catch (Exception err) {
		    err.printStackTrace();
		}
	}

	/**
	 * Initialise the contents of the frame.
	 */
	private void initialize() {
		frmSdkperfGui = new JFrame();
		frmSdkperfGui.setTitle("SDKPerfGUI");
		frmSdkperfGui.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		// I had to fix the height of the top cell to 254 px to stop the GUI shrinking and growing when hiding and showing the overrides pane
		// there may be a better way to do this, can't help feeling I'm fighting the layout rather than working with it
		frmSdkperfGui.getContentPane().setLayout(new MigLayout("insets 4px, hidemode 2, flowy", "[115.00px][112.00px][137.00][29.00px][171.00][][22.00,grow]", "[:254.00px:254.00px][16px][0.00px,grow]"));
		
		JTabbedPane tabbedPaneMain = new JTabbedPane(JTabbedPane.TOP);
		frmSdkperfGui.getContentPane().add(tabbedPaneMain, "cell 0 0 7 1");
		
		//SDKPerfControls
		createClientPanel(tabbedPaneMain);
		
		//Common Settings fields
		createCommonSettingsPanel(tabbedPaneMain);
		
		// Advanced Subscriber Fields
		createAdvancedSubscriberPanel(tabbedPaneMain);
		
		//Advanced Publisher fields
		createAdvancedPublisherPanel(tabbedPaneMain);
		
		// timer panel controls
		createTimerPanel(tabbedPaneMain);
		
		// Latency Fields
		createLatencyPanel(tabbedPaneMain);
		
		//REST
		createRESTPanel(tabbedPaneMain);
		
		//MQTT
		createMQTTPanel(tabbedPaneMain);
		
		// auto scroll & hide output
		addOutputControls(tabbedPaneMain);
				
		JMenuBar menuBar = new JMenuBar();
		frmSdkperfGui.setJMenuBar(menuBar);
		
		JMenu mnFile = new JMenu("File");
		menuBar.add(mnFile);
		
		JMenuItem mntmSave = new JMenuItem("Save");
		mnFile.add(mntmSave);
		mntmSave.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				toFile();
			}
		});

		JMenuItem mntmLoad = new JMenuItem("Load");
		mnFile.add(mntmLoad);
		mntmLoad.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				fromFile();
			}
		});
		
		JMenu mnHelp = new JMenu("Help");
		menuBar.add(mnHelp);
		
		JMenuItem mntmAbout = new JMenuItem("About");
		mnHelp.add(mntmAbout);
		mntmAbout.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				SDKPerfGUIAbout about = new SDKPerfGUIAbout();
				about.setVisible(true);
			}
		});
		
		// Overrides Pane - don't add to GUI now, it's added when the checkbox for overrides is selected
		createOverridesPanel(tabbedPaneMain);

		//add output windows for 3 publishers and 3 subscribers and their overrides
		currentFont = new Font("Courier New",Font.PLAIN,14);
		addClient(Publisher1, true,textFieldDestinationP1,rdbtnTopicP1, null, null, cmbxCoSP1);
		addClient(Publisher2, true,textFieldDestinationP2,rdbtnTopicP2, null, null, cmbxCoSP2);
		addClient(Publisher3, true,textFieldDestinationP3,rdbtnTopicP3, null, null, cmbxCoSP3);
 		addClient(Subscriber1, false,textFieldDestinationS1,rdbtnTopicS1, rdbtnBrowserS1, chckbxDeliverAlwaysS1, null);
		addClient(Subscriber2, false,textFieldDestinationS2,rdbtnTopicS2, rdbtnBrowserS2, chckbxDeliverAlwaysS2, null);
		addClient(Subscriber3, false,textFieldDestinationS3,rdbtnTopicS3, rdbtnBrowserS3, chckbxDeliverAlwaysS3, null);
				
		tabbedPanePublishers.setSelectedIndex(0);
		tabbedPanePublishers.setSelectedIndex(0);
		
		lblFont = new JLabel("Font");
		frmSdkperfGui.getContentPane().add(lblFont, "flowx,cell 3 1,alignx right");
		
		comboBoxFont= new JComboBox<String>();
		comboBoxFont.setModel(new DefaultComboBoxModel<String>(fontOptions));
		comboBoxFont.setSelectedItem(currentFont.getName());
		comboBoxFont.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				changeFont();
			}
		});
		frmSdkperfGui.getContentPane().add(comboBoxFont, "flowx,cell 4 1 2 1");
		
		comboBoxFontSize = new JComboBox<Integer>();
		comboBoxFontSize.setModel(new DefaultComboBoxModel<Integer>(sizeOptions));
		comboBoxFontSize.setSelectedItem(currentFont.getSize());
		comboBoxFontSize.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				changeFont();
			}
		});
		frmSdkperfGui.getContentPane().add(comboBoxFontSize, "cell 5 1");
		
		// set the common settings panel to be the first selected panel as changing ip addresses and users is likely to be the first thing someone will do
		tabbedPaneMain.setSelectedIndex(1);

		frmSdkperfGui.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				/// Tidy up any remaining processes if window closed without disabling them
				terminateAllClients();
			}
		});

	}

	private void createOverridesPanel(JTabbedPane tabbedPaneMain) {
		tpDestinationOverrides = new JTabbedPane(JTabbedPane.TOP);
		tpDestinationOverrides.setMaximumSize(new Dimension(100,100));
		tpDestinationOverrides.setBorder(new TitledBorder(new EtchedBorder(EtchedBorder.LOWERED, null, null), "Set Properties on a Per Client Basis", TitledBorder.LEADING, TitledBorder.TOP, null, new Color(0, 0, 0)));
		// Publishers
		
		JPanel panelPubOverrides = new JPanel();
		tpDestinationOverrides.addTab("Publisher", null, panelPubOverrides, null);

		panelPubOverrides.setLayout(new MigLayout("", "[][][][][]", "[][][][]"));
		
		JLabel labelPublisher = new JLabel("No.");
		panelPubOverrides.add(labelPublisher, "cell 0 0");
		
		JLabel labelPubDestination = new JLabel("Destination");
		labelPubDestination.setHorizontalAlignment(SwingConstants.CENTER);
		panelPubOverrides.add(labelPubDestination, "cell 1 0,alignx center");
		
		lblCosLevel = new JLabel("CoS Level");
		panelPubOverrides.add(lblCosLevel, "cell 3 0");
		
		JLabel lblPub1 = new JLabel("1");
		panelPubOverrides.add(lblPub1, "cell 0 1");
		
		textFieldDestinationP1 = new JTextField(MessageDestination);
		textFieldDestinationP1.setColumns(10);
		panelPubOverrides.add(textFieldDestinationP1, "cell 1 1,growx");
		
		rdbtnTopicP1 = new JRadioButton("Topic");
		buttonGroupTQP1.add(rdbtnTopicP1);
		rdbtnTopicP1.setSelected(true);
		panelPubOverrides.add(rdbtnTopicP1, "flowx,cell 2 1");
		
		cmbxCoSP1 = new JComboBox<String>();
		cmbxCoSP1.setModel(new DefaultComboBoxModel<String>(cosList));
		cmbxCoSP1.setSelectedIndex(1);
		panelPubOverrides.add(cmbxCoSP1, "cell 3 1,growx");
		
		JLabel labelPub2 = new JLabel("2");
		panelPubOverrides.add(labelPub2, "cell 0 2");
		
		textFieldDestinationP2 = new JTextField(MessageDestination);
		textFieldDestinationP2.setColumns(10);
		panelPubOverrides.add(textFieldDestinationP2, "cell 1 2,growx");
		
		rdbtnTopicP2 = new JRadioButton("Topic");
		buttonGroupTQP2.add(rdbtnTopicP2);
		rdbtnTopicP2.setSelected(true);
		panelPubOverrides.add(rdbtnTopicP2, "flowx,cell 2 2");
		
		cmbxCoSP2 = new JComboBox<String>();
		cmbxCoSP2.setModel(new DefaultComboBoxModel<String>(cosList));
		cmbxCoSP2.setSelectedIndex(1);
		panelPubOverrides.add(cmbxCoSP2, "cell 3 2,growx");
		
		JLabel labelPub3 = new JLabel("3");
		panelPubOverrides.add(labelPub3, "cell 0 3");
		
		textFieldDestinationP3 = new JTextField(MessageDestination);
		textFieldDestinationP3.setColumns(10);
		panelPubOverrides.add(textFieldDestinationP3, "cell 1 3,growx");
		
		rdbtnTopicP3 = new JRadioButton("Topic");
		buttonGroupTQP3.add(rdbtnTopicP3);
		rdbtnTopicP3.setSelected(true);
		panelPubOverrides.add(rdbtnTopicP3, "flowx,cell 2 3");
		
		JRadioButton rdbtnQueueP1 = new JRadioButton("Queue");
		buttonGroupTQP1.add(rdbtnQueueP1);
		panelPubOverrides.add(rdbtnQueueP1, "cell 2 1");
		
		JRadioButton rdbtnQueueP2 = new JRadioButton("Queue");
		buttonGroupTQP2.add(rdbtnQueueP2);
		panelPubOverrides.add(rdbtnQueueP2, "cell 2 2");
		
		JRadioButton rdbtnQueueP3 = new JRadioButton("Queue");
		buttonGroupTQP3.add(rdbtnQueueP3);
		panelPubOverrides.add(rdbtnQueueP3, "cell 2 3");
		
		cmbxCoSP3 = new JComboBox<String>();
		cmbxCoSP3.setModel(new DefaultComboBoxModel<String>(cosList));
		cmbxCoSP3.setSelectedIndex(1);
		panelPubOverrides.add(cmbxCoSP3, "cell 3 3,growx");
				
		// Subscribers
		JPanel panelSubOverrides = new JPanel();
		tpDestinationOverrides.addTab("Subscriber", null, panelSubOverrides, null);
		panelSubOverrides.setLayout(new MigLayout("", "[][][][][]", "[][][][]"));
				 
		JLabel labelSubscriber = new JLabel("No.");
		panelSubOverrides.add(labelSubscriber, "cell 0 0");
		
		JLabel labelSubDestination = new JLabel("Source");
		labelSubDestination.setHorizontalAlignment(SwingConstants.CENTER);
		panelSubOverrides.add(labelSubDestination, "cell 1 0,alignx center");
		
		JLabel lblSub1 = new JLabel("1");
		panelSubOverrides.add(lblSub1, "cell 0 1");
		
		textFieldDestinationS1 = new JTextField(MessageDestination);
		textFieldDestinationS1.setColumns(10);
		panelSubOverrides.add(textFieldDestinationS1, "cell 1 1,growx");
		
		rdbtnTopicS1 = new JRadioButton("Topic");
		buttonGroupTQS1.add(rdbtnTopicS1);
		rdbtnTopicS1.setSelected(true);

		panelSubOverrides.add(rdbtnTopicS1, "flowx,cell 2 1");
		
		chckbxDeliverAlwaysS1 = new JCheckBox("Deliver Always");
		panelSubOverrides.add(chckbxDeliverAlwaysS1, "cell 3 1");
		
		JLabel labelSub2 = new JLabel("2");
		panelSubOverrides.add(labelSub2, "cell 0 2");
		
		textFieldDestinationS2 = new JTextField(MessageDestination);
		textFieldDestinationS2.setColumns(10);
		panelSubOverrides.add(textFieldDestinationS2, "cell 1 2,growx");
		
		rdbtnTopicS2 = new JRadioButton("Topic");
		buttonGroupTQS2.add(rdbtnTopicS2);
		rdbtnTopicS2.setSelected(true);
		panelSubOverrides.add(rdbtnTopicS2, "flowx,cell 2 2");
		
		chckbxDeliverAlwaysS2 = new JCheckBox("Deliver Always");
		panelSubOverrides.add(chckbxDeliverAlwaysS2, "cell 3 2");
		
		JLabel labelSub3 = new JLabel("3");
		panelSubOverrides.add(labelSub3, "cell 0 3");
		
		textFieldDestinationS3 = new JTextField(MessageDestination);
		textFieldDestinationS3.setColumns(10);
		panelSubOverrides.add(textFieldDestinationS3, "cell 1 3,growx");
		
		rdbtnTopicS3 = new JRadioButton("Topic");
		buttonGroupTQS3.add(rdbtnTopicS3);
		rdbtnTopicS3.setSelected(true);
		panelSubOverrides.add(rdbtnTopicS3, "flowx,cell 2 3");
		
		JRadioButton rdbtnQueueS1 = new JRadioButton("Queue");
		buttonGroupTQS1.add(rdbtnQueueS1);
		panelSubOverrides.add(rdbtnQueueS1, "cell 2 1");
		
		JRadioButton rdbtnQueueS2 = new JRadioButton("Queue");
		buttonGroupTQS2.add(rdbtnQueueS2);
		panelSubOverrides.add(rdbtnQueueS2, "cell 2 2");
		
		JRadioButton rdbtnQueueS3 = new JRadioButton("Queue");
		buttonGroupTQS3.add(rdbtnQueueS3);
		panelSubOverrides.add(rdbtnQueueS3, "cell 2 3");
		
		rdbtnBrowserS1 = new JRadioButton("Browser");
		buttonGroupTQS1.add(rdbtnBrowserS1);
		panelSubOverrides.add(rdbtnBrowserS1, "cell 2 1");
		
		rdbtnBrowserS2 = new JRadioButton("Browser");
		buttonGroupTQS2.add(rdbtnBrowserS2);
		panelSubOverrides.add(rdbtnBrowserS2, "cell 2 2");
		
		rdbtnBrowserS3 = new JRadioButton("Browser");
		buttonGroupTQS3.add(rdbtnBrowserS3);
		panelSubOverrides.add(rdbtnBrowserS3, "cell 2 3");
		
		chckbxDeliverAlwaysS3 = new JCheckBox("Deliver Always");
		panelSubOverrides.add(chckbxDeliverAlwaysS3, "cell 3 3");
		
//keep this line here, need to uncomment to make the panel visible in the designer
//		tabbedPaneMain.addTab("Overrides", null, tpDestinationOverrides, null);
	}

	private void addOutputControls(JTabbedPane tabbedPaneMain) {
		JToggleButton tglbtnAutoScroll = new JToggleButton("Auto Scroll");
		tglbtnAutoScroll.setSelected(false);
		tglbtnAutoScroll.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (tglbtnAutoScroll.isSelected()) {
					enableAutoScroll(true);
				} else {
					enableAutoScroll(false);
				}
			}
		});

		frmSdkperfGui.getContentPane().add(tglbtnAutoScroll, "cell 1 1");
		
		///////////
		
		// set up auto scrolling
		messagesAdjustmentListener = new AdjustmentListener() {
			@Override
	        public void adjustmentValueChanged(AdjustmentEvent e) {  
	            e.getAdjustable().setValue(e.getAdjustable().getMaximum());  
	        }
		};
				
		///////////
			
		JToggleButton tglbtnHideOutput = new JToggleButton("Hide Output");
		tglbtnHideOutput.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (tglbtnHideOutput.getSelectedObjects() == null) {
					// show output
					frmSdkperfGui.setResizable(true);
					tabbedPanePublishers.setVisible(true);
					// if subscriber output is hidden because of single client mode, don't make them visible now
					if (!isSingleClientMode()) {
						tabbedPaneSubscribers.setVisible(true);
						splitPane.setDividerLocation(0.5);
					}
					tglbtnAutoScroll.setVisible(true);
					comboBoxFont.setVisible(true);
					comboBoxFontSize.setVisible(true);
					lblFont.setVisible(true);
					splitPane.setVisible(true);
					frmSdkperfGui.setSize(initialSize);
					frmSdkperfGui.revalidate();
//					frmSdkperfGui.pack();
				} else {
					// hide output
					tabbedPanePublishers.setVisible(false);
					tabbedPaneSubscribers.setVisible(false);					
					tglbtnAutoScroll.setVisible(false);
					comboBoxFont.setVisible(false);
					comboBoxFontSize.setVisible(false);
					lblFont.setVisible(false);
					splitPane.setVisible(false);
					frmSdkperfGui.setSize(minimumSize);
					frmSdkperfGui.revalidate();
//					frmSdkperfGui.pack();
					frmSdkperfGui.setResizable(false);
				}
			}
		});
		frmSdkperfGui.getContentPane().add(tglbtnHideOutput, "cell 0 1");
	}

	private void createLatencyPanel(JTabbedPane tabbedPaneMain) {
		
		JPanel panelLatency = new JPanel();
		panelLatency.setBorder(new TitledBorder(null, "Latency Measurement Options", TitledBorder.LEADING, TitledBorder.TOP, null, null));
		tabbedPaneMain.addTab("Latency", null, panelLatency, null);
		panelLatency.setLayout(new MigLayout("insets 0", "[][][]", "[][][]"));
		
		chckbxEnableSingleClient = new JCheckBox("Enable Single Client Mode");
		chckbxEnableSingleClient.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (chckbxEnableSingleClient.isSelected()) {
					// disable 'enable' buttons on subscriber controls
					Subscriber1.callSetEnableOnControlButtons(false);
					Subscriber2.callSetEnableOnControlButtons(false);
					Subscriber3.callSetEnableOnControlButtons(false);
					// change label text for latency mode
					lblPublishers.setText("Latency Single Client Mode: Publish Settings");					
					lblSubscribers.setText("Latency Single Client Mode: Subscribe Settings");
					//tabbedPaneSubscribers.setEnabled(false);
					tabbedPaneSubscribers.setVisible(false);
				} else {
					// re-enable all the buttons
					Subscriber1.callSetEnableOnControlButtons(true);
					Subscriber2.callSetEnableOnControlButtons(true);
					Subscriber3.callSetEnableOnControlButtons(true);
					// return heading text to normal
					lblPublishers.setText("Publishers");					
					lblSubscribers.setText("Subscribers");
					//tabbedPaneSubscribers.setEnabled(true);
					tabbedPaneSubscribers.setVisible(true);
					splitPane.setDividerLocation(0.5);
				}
				if (clientsAreEnabled()) {
					infoBox("Single Client Mode cannot be enabled/disabled while clients are running.\rTerminating running clients.","Clients Still Running");
					terminateAllClients();
				}
			} 
		});

		chckbxEnableLatencyMeasurement = new JCheckBox("Measure Latency");
		chckbxEnableLatencyMeasurement.addActionListener(new ActionListener() {
		public void actionPerformed(ActionEvent e) {
				if (chckbxEnableLatencyMeasurement.isSelected()) {
					//enable the other latency controls
					chckbxPrintStats.setEnabled(true);
					txtLatencyGranularity.setEnabled(true);
					chckbxEnableSingleClient.setEnabled(true);
					cmbobxLatencyBuckets.setEnabled(true);
				} else {
					//disable the other latency controls
					chckbxPrintStats.setEnabled(false);
					txtLatencyGranularity.setEnabled(false);
					if (isSingleClientMode()) {
						chckbxEnableSingleClient.doClick();;
					}
					chckbxEnableSingleClient.setEnabled(false);
					cmbobxLatencyBuckets.setEnabled(false);
				}
			}
		});
		panelLatency.add(chckbxEnableLatencyMeasurement, "cell 0 0");
		
		chckbxPrintStats = new JCheckBox("Print Stats");
		panelLatency.add(chckbxPrintStats, "cell 1 0");
		
		panelLatency.add(chckbxEnableSingleClient, "cell 2 0");
		
		lblLatencyGranularity = new JLabel();
		lblLatencyGranularity.setText("Latency Granularity");
		panelLatency.add(lblLatencyGranularity, "cell 0 1,alignx center");

		lblLatencyBuckets = new JLabel();
		lblLatencyBuckets.setText("No. Latency Buckets");
		panelLatency.add(lblLatencyBuckets, "cell 0 2,alignx center");
		
		txtLatencyGranularity = new JTextField("0");
		panelLatency.add(txtLatencyGranularity, "cell 1 1");
		txtLatencyGranularity.setColumns(10);

		cmbobxLatencyBuckets = new JComboBox<>(latencyBucketValues);
		panelLatency.add(cmbobxLatencyBuckets, "cell 1 2");
		cmbobxLatencyBuckets.setSelectedIndex(0);

		// disable latency controls to start (re-enabled when 'enable latency' is selected)
		chckbxPrintStats.setEnabled(false);
		txtLatencyGranularity.setEnabled(false);
		chckbxEnableSingleClient.setEnabled(false);
		cmbobxLatencyBuckets.setEnabled(false);
	}
	private void createRESTPanel(JTabbedPane tabbedPaneMain) {
		
		JPanel panelREST = new JPanel();
		panelREST.setBorder(new TitledBorder(null, "REST Specific Options", TitledBorder.LEADING, TitledBorder.TOP, null, null));
		tabbedPaneMain.addTab("REST", null, panelREST, null);
		panelREST.setLayout(new MigLayout("", "[][][]", "[][][][][][]"));
		
		lblRestPort = new JLabel("REST Publisher Port");
		panelREST.add(lblRestPort, "cell 1 0,alignx trailing");
		
		textFieldRESTPort = new JTextField("9000");
		panelREST.add(textFieldRESTPort, "cell 2 0,growx");
		textFieldRESTPort.setColumns(10);
		
		JLabel lblServerPortList = new JLabel("REST Subscriber Port List");
		panelREST.add(lblServerPortList, "cell 1 1,alignx trailing");
		
		textFieldRESTSvrPortList = new JTextField("5678");
		panelREST.add(textFieldRESTSvrPortList, "cell 2 1,growx");
		textFieldRESTSvrPortList.setColumns(10);
		textFieldRESTSvrPortList.setEnabled(false);
		
		JLabel lblClientMode = new JLabel("Client Mode");
		panelREST.add(lblClientMode, "cell 1 2,alignx trailing");
		
		comboBoxRESTClientMode = new JComboBox<String>();
		comboBoxRESTClientMode.setModel(new DefaultComboBoxModel<String>(new String[] {"HttpClient", "Socket"}));
		panelREST.add(comboBoxRESTClientMode, "cell 2 2,growx");
		comboBoxRESTClientMode.setEnabled(false);
		
		JLabel lblRequestReplyWait = new JLabel("Request Reply Wait Time");
		panelREST.add(lblRequestReplyWait, "cell 1 3,alignx trailing");
		
		textFieldRESTRRWaitTime = new JTextField();
		panelREST.add(textFieldRESTRRWaitTime, "cell 2 3,growx");
		textFieldRESTRRWaitTime.setColumns(10);
		textFieldRESTRRWaitTime.setEnabled(false);
		
		JLabel lblRestlocalIp = new JLabel("REST (local) IP list");
		panelREST.add(lblRestlocalIp, "cell 1 4,alignx trailing");
		
		textFieldRESTLocalIPList = new JTextField();
		panelREST.add(textFieldRESTLocalIPList, "cell 2 4,growx");
		textFieldRESTLocalIPList.setColumns(10);		
		
		chckbxEnableRest = new JCheckBox("Enable REST");
		chckbxEnableRest.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (chckbxEnableRest.isSelected()) {
					textFieldRESTLocalIPList.setEnabled(true);
					textFieldRESTRRWaitTime.setEnabled(true);
					textFieldRESTSvrPortList.setEnabled(true);
					comboBoxRESTClientMode.setEnabled(true);
					textFieldRESTLocalIPList.setEnabled(true);
					textFieldRESTPort.setEnabled(true);
					// I think it makes sense to automatically disable MQTT if REST is selected and vice-versa
					if (isMQTT()) {
						chckbxEnableMqtt.doClick();
					}
				} else {
					textFieldRESTLocalIPList.setEnabled(false);
					textFieldRESTRRWaitTime.setEnabled(false);
					textFieldRESTSvrPortList.setEnabled(false);
					comboBoxRESTClientMode.setEnabled(false);
					textFieldRESTLocalIPList.setEnabled(false);
					textFieldRESTPort.setEnabled(false);
				}
				// we also need to terminate all running clients if the mode is changed
				if (clientsAreEnabled()) {
					infoBox("REST Mode cannot be enabled/disabled while clients are running.\rTerminating running clients.","Clients Still Running");
					terminateAllClients();
				}
			}
		});
		
		panelREST.add(chckbxEnableRest, "cell 0 0");	
		
		// disable all components to start with
		textFieldRESTPort.setEnabled(false);
		textFieldRESTLocalIPList.setEnabled(false);
		textFieldRESTRRWaitTime.setEnabled(false);
		textFieldRESTSvrPortList.setEnabled(false);
		comboBoxRESTClientMode.setEnabled(false);
		textFieldRESTLocalIPList.setEnabled(false);
	}
	
	private void createMQTTPanel(JTabbedPane tabbedPaneMain) {
		
		JPanel panelMQTT = new JPanel();
		panelMQTT.setBorder(new TitledBorder(null, "MQTT Specific Options", TitledBorder.LEADING, TitledBorder.TOP, null, null));
		tabbedPaneMain.addTab("MQTT", null, panelMQTT, null);
		panelMQTT.setLayout(new MigLayout("", "[][][][][]", "[][][][][][]"));
				
		chckbxCleanSession = new JCheckBox("Clean Session");
		panelMQTT.add(chckbxCleanSession, "cell 1 0");
		// clean session is the default but this screws up persistence (QoS 1) so set it off by default
		chckbxCleanSession.setSelected(false);
		
		chckbxRetainWillMessage = new JCheckBox("Retain Will Message");
		panelMQTT.add(chckbxRetainWillMessage, "cell 4 0");
		
		lblMqttPort = new JLabel("MQTT Port");
		panelMQTT.add(lblMqttPort, "cell 0 1,alignx trailing");

		textFieldMQTTPort = new JTextField("1883");
		panelMQTT.add(textFieldMQTTPort, "cell 1 1,growx");
		textFieldMQTTPort.setColumns(10);
		textFieldMQTTPort.setEnabled(false);

		JLabel lblWillQos = new JLabel("Will QoS");
		panelMQTT.add(lblWillQos, "cell 3 1,alignx trailing");
		
		comboBoxMQTTWillQoS = new JComboBox<String>();
		comboBoxMQTTWillQoS.setModel(new DefaultComboBoxModel<String>(new String[] {"0", "1", "2"}));
		panelMQTT.add(comboBoxMQTTWillQoS, "cell 4 1,growx");
		
		JLabel lblWillTopic = new JLabel("Will Topic");
		panelMQTT.add(lblWillTopic, "cell 3 2,alignx trailing");
		
		txtTopicmqttwill = new JTextField();
		txtTopicmqttwill.setText("topic/mqtt/will");
		panelMQTT.add(txtTopicmqttwill, "cell 4 2,growx");
		txtTopicmqttwill.setColumns(10);
		
		JLabel lblWillMessageSize = new JLabel("Will Msg Size");
		panelMQTT.add(lblWillMessageSize, "cell 3 3,alignx trailing");
		
		textFieldWillMessageSize = new JTextField();
		textFieldWillMessageSize.setText("10");
		panelMQTT.add(textFieldWillMessageSize, "cell 4 3,growx");
		textFieldWillMessageSize.setColumns(10);	
		
		chckbxMqttWill = new JCheckBox("MQTT Will");
		chckbxMqttWill.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (chckbxMqttWill.isSelected()) {
					chckbxRetainWillMessage.setEnabled(true);
					comboBoxMQTTWillQoS.setEnabled(true);
					txtTopicmqttwill.setEnabled(true);
					textFieldWillMessageSize.setEnabled(true);
				} else {
					chckbxRetainWillMessage.setEnabled(false);
					comboBoxMQTTWillQoS.setEnabled(false);
					txtTopicmqttwill.setEnabled(false);
					textFieldWillMessageSize.setEnabled(false);
				}
			}
		});
		panelMQTT.add(chckbxMqttWill, "cell 3 0");

		lblMqttClientVer = new JLabel("MQTT Client Ver");
		panelMQTT.add(lblMqttClientVer, "cell 0 2,alignx trailing");

		rdbtnMQTTv3 = new JRadioButton("3");
		rdbtnMQTTVer.add(rdbtnMQTTv3);
		rdbtnMQTTv3.setSelected(true);
		panelMQTT.add(rdbtnMQTTv3, "flowx,cell 1 2");
		rdbtnMQTTv5 = new JRadioButton("5");
		rdbtnMQTTVer.add(rdbtnMQTTv5);
		panelMQTT.add(rdbtnMQTTv5, "flowx,cell 1 3");

		chckbxEnableMqtt = new JCheckBox("Enable MQTT");
		chckbxEnableMqtt.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (chckbxEnableMqtt.isSelected()) {
					chckbxCleanSession.setEnabled(true);
					chckbxMqttWill.setEnabled(true);
					textFieldMQTTPort.setEnabled(true);
					rdbtnMQTTv3.setEnabled(true);
					rdbtnMQTTv5.setEnabled(true);
					changeAllPersistenceModels(true);
					// I think it makes sense to automatically disable MQTT if REST is selected and vice-versa
					if (isREST()) {
						chckbxEnableRest.doClick();
					}
				} else {
					chckbxCleanSession.setEnabled(false);
					chckbxMqttWill.setEnabled(false);					
					changeAllPersistenceModels(false);
					rdbtnMQTTv3.setEnabled(false);
					rdbtnMQTTv5.setEnabled(false);
					textFieldMQTTPort.setEnabled(false);
				}
				// we also need to terminate all running clients if the mode is changed
				if (clientsAreEnabled()) {
					infoBox("MQTT Mode cannot be enabled/disabled while clients are running.\rTerminating running clients.","Clients Still Running");
					terminateAllClients();
				}
			}
		});
		
		// Disable all components to start
		chckbxCleanSession.setEnabled(false);
		chckbxMqttWill.setEnabled(false);					
		chckbxRetainWillMessage.setEnabled(false);
		comboBoxMQTTWillQoS.setEnabled(false);
		txtTopicmqttwill.setEnabled(false);
		textFieldWillMessageSize.setEnabled(false);
		rdbtnMQTTv3.setEnabled(false);
		rdbtnMQTTv5.setEnabled(false);
		textFieldMQTTPort.setEnabled(false);

		panelMQTT.add(chckbxEnableMqtt, "cell 0 0");
	}

	private void changeAllPersistenceModels(boolean isQoS) {
		for (SDKPerfControl control : sdkPerfControls.values()) {
		    control.changePersistenceModel(isQoS);
		}	
	}

	private void createClientPanel(JTabbedPane tabbedPaneMain) {
		JPanel Clients = new JPanel();
		tabbedPaneMain.addTab("sdkPerf", null, Clients, null);
		Clients.setLayout(new MigLayout("insets 0", "[173px][305.00]", "[16px][][][]"));

		tabbedPanePublishers = new JTabbedPane(JTabbedPane.TOP);
				
		tabbedPaneSubscribers = new JTabbedPane(JTabbedPane.TOP);
		
		rigidArea = Box.createRigidArea(new Dimension(130, 20));
		frmSdkperfGui.getContentPane().add(rigidArea, "cell 2 1");
		
		splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT ,tabbedPanePublishers , tabbedPaneSubscribers);
		splitPane.setResizeWeight(0.5);
		frmSdkperfGui.getContentPane().add(splitPane, "cell 0 2 7 1,grow");

		lblPublishers = new JLabel("Publishers");
		Clients.add(lblPublishers, "cell 0 0,alignx center,aligny top");
		lblPublishers.setHorizontalAlignment(SwingConstants.CENTER);
		
		lblSubscribers = new JLabel("Subscribers");
		Clients.add(lblSubscribers, "cell 1 0,alignx center,aligny top");
		lblSubscribers.setHorizontalAlignment(SwingConstants.CENTER);
		
		Publisher1 = new SDKPerfControl("Publisher1", true, this);
		Clients.add(Publisher1, "cell 0 1");
		
		Subscriber1 = new SDKPerfControl("Subscriber1", false, this);
		Clients.add(Subscriber1, "cell 1 1");
		Publisher1.addPairedSubscriber(Subscriber1);
		
		Publisher2 = new SDKPerfControl("Publisher2", true, this);
		Clients.add(Publisher2, "cell 0 2");
		
		Subscriber2 = new SDKPerfControl("Subscriber2", false, this);
		Clients.add(Subscriber2, "cell 1 2");
		Publisher2.addPairedSubscriber(Subscriber2);
		
		Publisher3 = new SDKPerfControl("Publisher3", true, this);
		Clients.add(Publisher3, "cell 0 3");
		
		Subscriber3 = new SDKPerfControl("Subscriber3", false, this);
		Clients.add(Subscriber3, "cell 1 3");
		Publisher3.addPairedSubscriber(Subscriber3);
	}

	private void createTimerPanel(JTabbedPane tabbedPaneMain) {
		JPanel panelTimed = new JPanel();
		panelTimed.setBorder(new TitledBorder(new EtchedBorder(EtchedBorder.LOWERED, null, null), "Timer Options", TitledBorder.LEADING, TitledBorder.TOP, null, new Color(0, 0, 0)));
		tabbedPaneMain.addTab("Timed", null, panelTimed, null);
		panelTimed.setLayout(new MigLayout("insets 0", "[]", "[][][][]"));
		
		chckbxEnableTimer = new JCheckBox("Enable Timed Execution");
		chckbxEnableTimer.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (chckbxEnableTimer.isSelected()) {
					// disable message count, enable timer control
					txtMessageCount.setEnabled(false);
					timeSpinner.setEnabled(true);
				} else {
					// re-enable message count
					txtMessageCount.setEnabled(true);
					timeSpinner.setEnabled(false);
				}
			}
		});
		panelTimed.add(chckbxEnableTimer, "cell 0 0");
		
		JLabel lblLengthOfTime = new JLabel("HH:mm:ss");
		panelTimed.add(lblLengthOfTime, "cell 0 2");
		
		timeSpinner = new JSpinner( new SpinnerDateModel() );
		// this is a simple way to get a time based control (we are ignoring the date part, so any value will do)
		JSpinner.DateEditor timeEditor = new JSpinner.DateEditor(timeSpinner, "HH:mm:ss");
		timeSpinner.setEditor(timeEditor);
		baseDate = new Date(70, 0, 1);				
		timeSpinner.setValue(baseDate);
		// disabled by default until checkbox ticked
		timeSpinner.setEnabled(false);
		panelTimed.add(timeSpinner, "cell 0 3");
	}

	private void createAdvancedPublisherPanel(JTabbedPane tabbedPaneMain) {
		JPanel panelAdvancedPublisher = new JPanel();
		panelAdvancedPublisher.setBorder(new TitledBorder(new EtchedBorder(EtchedBorder.LOWERED, null, null), "Publisher Only Options", TitledBorder.LEADING, TitledBorder.TOP, null, new Color(0, 0, 0)));
		tabbedPaneMain.addTab("Publisher", null, panelAdvancedPublisher, null);
		panelAdvancedPublisher.setLayout(new MigLayout("insets 0", "[][133.00][][23.00][][]", "[][][][][]"));
		
		JLabel lblFastSpeed = new JLabel("Fast Speed");
		panelAdvancedPublisher.add(lblFastSpeed, "cell 0 0,alignx trailing");
		
		txtFastPublishSpeed = new JTextField();
		txtFastPublishSpeed.setText("1000");
		panelAdvancedPublisher.add(txtFastPublishSpeed, "cell 1 0,growx");
		txtFastPublishSpeed.setColumns(10);
		
		JLabel lblsec = new JLabel("/Sec");
		panelAdvancedPublisher.add(lblsec, "cell 2 0");
		
		chckbxDeliverToOne = new JCheckBox("Deliver To One");
		panelAdvancedPublisher.add(chckbxDeliverToOne, "cell 4 0");
		
		JLabel lblSlowSpeed = new JLabel("Slow Speed");
		panelAdvancedPublisher.add(lblSlowSpeed, "cell 0 1,alignx trailing");
		
		txtSlowPublishSpeed = new JTextField();
		txtSlowPublishSpeed.setText("10");
		panelAdvancedPublisher.add(txtSlowPublishSpeed, "cell 1 1,growx");
		txtSlowPublishSpeed.setColumns(10);
		
		JLabel lblsec_1 = new JLabel("/Sec");
		panelAdvancedPublisher.add(lblsec_1, "cell 2 1");
		
		chckbxRequestreply = new JCheckBox("Request/Reply");
		chckbxRequestreply.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				chckbxShowReply.setEnabled(chckbxRequestreply.isSelected());
			}
		});
		panelAdvancedPublisher.add(chckbxRequestreply, "cell 4 1");
		
		chckbxShowReply = new JCheckBox("Show Reply");
		chckbxShowReply.setEnabled(false);
		panelAdvancedPublisher.add(chckbxShowReply, "cell 5 1");
		
		chckbxQuietPub = new JCheckBox("Quiet");
		panelAdvancedPublisher.add(chckbxQuietPub, "cell 4 2");
		
		chckbxXmlAttachments = new JCheckBox("XML Attachments");
		panelAdvancedPublisher.add(chckbxXmlAttachments, "cell 4 3");
	}

	private void createAdvancedSubscriberPanel(JTabbedPane tabbedPaneMain) {
		JPanel panelAdvancedSubscriber = new JPanel();
		panelAdvancedSubscriber.setBorder(new TitledBorder(new EtchedBorder(EtchedBorder.LOWERED, null, null), "Subscriber Only Options", TitledBorder.LEADING, TitledBorder.TOP, null, new Color(0, 0, 0)));
		tabbedPaneMain.addTab("Subscriber", null, panelAdvancedSubscriber, null);
		panelAdvancedSubscriber.setLayout(new MigLayout("insets 0", "[][166.00][]", "[][][][]"));
		
		JLabel lblFastDelay = new JLabel("Fast Delay ms");
		panelAdvancedSubscriber.add(lblFastDelay, "cell 0 0,alignx trailing");
		
		txtFastSubscriberDelay = new JTextField();
		txtFastSubscriberDelay.setText("0");
		panelAdvancedSubscriber.add(txtFastSubscriberDelay, "cell 1 0,growx");
		txtFastSubscriberDelay.setColumns(10);
		
		chckbxReplyMode = new JCheckBox("Reply Mode");
		panelAdvancedSubscriber.add(chckbxReplyMode, "cell 2 0");
		
		JLabel lblSlowDelay = new JLabel("Slow Delay ms");
		panelAdvancedSubscriber.add(lblSlowDelay, "cell 0 1,alignx trailing");
		
		txtSlowSubscriberDelay = new JTextField();
		txtSlowSubscriberDelay.setText("100");
		panelAdvancedSubscriber.add(txtSlowSubscriberDelay, "cell 1 1,growx");
		txtSlowSubscriberDelay.setColumns(10);
		
		chckbxQuietSub = new JCheckBox("Quiet");
		panelAdvancedSubscriber.add(chckbxQuietSub, "flowx,cell 2 1");
		
		chckbxProvisionEndpoints = new JCheckBox("Provision Endpoints");
		chckbxProvisionEndpoints.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (chckbxProvisionEndpoints.isSelected()) {
					chckbxNonExclusive.setEnabled(true);
				} else {
					chckbxNonExclusive.setEnabled(false);					
				}
			}
		});
		
		chckbxCallbackOnReactor = new JCheckBox("Callback On Reactor Thread");
		panelAdvancedSubscriber.add(chckbxCallbackOnReactor, "cell 2 2");
		// we don't want people to be faffing around with queues so make provision the default
		chckbxProvisionEndpoints.setSelected(true);
		panelAdvancedSubscriber.add(chckbxProvisionEndpoints, "flowx,cell 2 3");
		
		chckbxNonExclusive = new JCheckBox("Provision Non Exclusive");
		panelAdvancedSubscriber.add(chckbxNonExclusive, "cell 2 3");
	}

	private void createCommonSettingsPanel(JTabbedPane tabbedPaneMain) {
		JPanel panelCommonSettings = new JPanel();
		tabbedPaneMain.addTab("Common", null, panelCommonSettings, null);
		panelCommonSettings.setBorder(new TitledBorder(null, "Common Settings", TitledBorder.LEADING, TitledBorder.TOP, null, null));
		panelCommonSettings.setLayout(new MigLayout("insets 0", "[]0px[325.00][][89.00,grow]", "[]0px[][][][][]"));
		
		JLabel lblRouterAddress = new JLabel("Router Address");
		panelCommonSettings.add(lblRouterAddress, "cell 0 0,grow");
		
		Address = new JTextField();
		panelCommonSettings.add(Address, "flowx,cell 1 0,grow");
		Address.setText(RouterAddress);
		Address.setColumns(10);

		JLabel lblNumberOfMessages = new JLabel("Message Count");
		lblNumberOfMessages.setHorizontalAlignment(SwingConstants.LEFT);
		panelCommonSettings.add(lblNumberOfMessages, "cell 2 0,alignx left");
		
		txtMessageCount = new JTextField();
		txtMessageCount.setText("100000");
		panelCommonSettings.add(txtMessageCount, "cell 3 0");
		txtMessageCount.setColumns(10);
				
		JLabel lblUser = new JLabel("User");
		panelCommonSettings.add(lblUser, "cell 0 1,grow");
		
		User = new JTextField();
		panelCommonSettings.add(User, "flowx,cell 1 1,grow");
		User.setText("default");
		User.setColumns(10);

		lblRetryCount = new JLabel("Retry Count");
		panelCommonSettings.add(lblRetryCount, "cell 2 1,alignx left");

		txtRetryCount = new JTextField();
		txtRetryCount.setText("10");
		panelCommonSettings.add(txtRetryCount, "cell 3 1");
		txtRetryCount.setColumns(10);
		
		JLabel lblPassword = new JLabel("Password");
		panelCommonSettings.add(lblPassword, "cell 0 2,grow");
		
		txtPassword = new JPasswordField();
		txtPassword.setColumns(10);
		panelCommonSettings.add(txtPassword, "flowx,cell 1 2,grow");
		
		JLabel lblVpn = new JLabel("VPN");
		panelCommonSettings.add(lblVpn, "cell 0 3,grow");
		
		VPN = new JTextField();
		panelCommonSettings.add(VPN, "cell 1 3,grow");
		VPN.setText("default");
		VPN.setColumns(10);
		
		JLabel lblDestination = new JLabel("Destination");
		panelCommonSettings.add(lblDestination, "cell 0 4,grow");
		
		Destination = new JTextField();
		panelCommonSettings.add(Destination, "flowx,cell 1 4,grow");
		Destination.setText(MessageDestination);
		Destination.setColumns(10);
		
		JRadioButton rdbtnTopic = new JRadioButton("Topic");
		rdbtnTopic.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				isTopic=true;
			}
		});
		btngrpTopicQueue.add(rdbtnTopic);
		panelCommonSettings.add(rdbtnTopic, "cell 1 4,growx 0,alignx right,aligny center");
		rdbtnTopic.setSelected(true);
		
		JRadioButton rdbtnQueue = new JRadioButton("Queue");
		rdbtnQueue.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				isTopic = false;
			}
		});
		btngrpTopicQueue.add(rdbtnQueue);
		panelCommonSettings.add(rdbtnQueue, "cell 1 4,growx,aligny center");

		chckbxOrderCheck = new JCheckBox("Order Check");
		panelCommonSettings.add(chckbxOrderCheck, "cell 2 4");

		chckbxEnableTLS = new JCheckBox("TLS");
		panelCommonSettings.add(chckbxEnableTLS, "cell 2 2");

		chckbxEnablePerClient = new JCheckBox("Enable Per Client Overrides");
		chckbxEnablePerClient.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				// Display the Overrides Panel
				if (chckbxEnablePerClient.isSelected()) {
					tabbedPaneMain.addTab("Overrides", null, tpDestinationOverrides, null);
				} else {
					// Hide the Overrides Panel
					tabbedPaneMain.remove(tpDestinationOverrides);
				}
			}
		});
		panelCommonSettings.add(chckbxEnablePerClient, "cell 1 5");
	}
	// Browser only applicable to subscribers so for producers can be null
	private void addClient( SDKPerfControl control, boolean bPublisher, JTextField OverrideDest, JRadioButton OverrideTQ, JRadioButton Browser, JCheckBox DA, JComboBox<String> CoSLevel) {
		JTabbedPane tabbedPane = null;
		String ClientName = null;
		if (bPublisher) {
			publishersCount++;
			ClientName = "Publisher"+publishersCount;
			tabbedPane = tabbedPanePublishers;
			OverrideCoSLevel.put(ClientName, CoSLevel);
		} else {
			subscribersCount++;
			ClientName = "Subscriber"+subscribersCount;	
			tabbedPane = tabbedPaneSubscribers;
			OverrideBrowser.put(ClientName, Browser);
			OverrideDA.put(ClientName, DA);
		}
		JScrollPane scrollPaneClient = new JScrollPane();

		// add text area to map		
		JTextArea textAreaClient = new JTextArea();
		textAreaClient.addMouseListener(new RightClickMenu());
		scrollPaneClient.setViewportView(textAreaClient);
		textAreaClient.setBackground(SystemColor.text);
		textAreaClient.setEditable(false);
		textAreaClient.setFont(currentFont);
		ClientTextAreas.put(ClientName, textAreaClient);
		
		tabbedPane.add(ClientName, scrollPaneClient);
		
		// add override controls to a map for retrieval later
		OverrideDestinations.put(ClientName, OverrideDest);
		OverrideTQs.put(ClientName, OverrideTQ);
		
		// add controls to a map too
		sdkPerfControls.put(ClientName, control);
		return;
	}

	public int setOutputDisplayTab(String controlName, boolean bPublisher) {
		int ClientNumber= 0;
		
		if (bPublisher) {
			ClientNumber = Integer.parseInt(controlName.substring(9)) - 1;
			tabbedPanePublishers.setSelectedIndex(ClientNumber);
		} else {
			ClientNumber = Integer.parseInt(controlName.substring(10)) - 1;
			tabbedPaneSubscribers.setSelectedIndex(ClientNumber);
		}
		return ClientNumber;
	}

	public boolean isOverrides() {
		return chckbxEnablePerClient.isSelected();
	}
	
	protected void enableAutoScroll(boolean autoUpdate) {
		if (autoUpdate) {
			// iterate through tabs
			for (int i=0; i < 3; i++) {
				((JScrollPane)tabbedPanePublishers.getComponentAt(i)).getVerticalScrollBar().addAdjustmentListener(messagesAdjustmentListener);
				((JScrollPane)tabbedPaneSubscribers.getComponentAt(i)).getVerticalScrollBar().addAdjustmentListener(messagesAdjustmentListener);
			}			
		} else {
			// iterate through tabs
			for (int i=0; i < 3; i++) {
				((JScrollPane)tabbedPanePublishers.getComponentAt(i)).getVerticalScrollBar().removeAdjustmentListener(messagesAdjustmentListener);
				((JScrollPane)tabbedPaneSubscribers.getComponentAt(i)).getVerticalScrollBar().removeAdjustmentListener(messagesAdjustmentListener);
			}			
		}
	}
	
	private void toFile(){
		infoBox("Saving settings not implemented yet.", "Not Implemented");
		return;
		// Clients
		// Common Settings
		// Advanced Subscriber
		// Advanced Publisher
		// Timer?
		// Latency
		// REST
		// MQTT
		// Overrides
		
	}
	private void fromFile() {
		infoBox("Saving settings not implemented yet.", "Not Implemented");
	}

	public String getMQTTWillQoS() {
		return (String)comboBoxMQTTWillQoS.getSelectedItem();
	}

	public String getRESTPort() {
		return textFieldRESTPort.getText();
	}
	
	public String getMQTTPort() {
		return textFieldMQTTPort.getText();
	}
	
	public String getRESTClientMode() {
		return (String)comboBoxRESTClientMode.getSelectedItem();
	}
	
	public String getMQTTCleanSession() {
		if (chckbxCleanSession.isSelected()) {
			return "1";
		} else {
			return "0";
		}
	}

	public boolean isMQTTRetainWillMessage() {
		return chckbxRetainWillMessage.isSelected();
	}
	
	public String getMQTTWillTopic() {
		return txtTopicmqttwill.getText();
	}
	
	public String getMQTTWillMessageSize() {
		return textFieldWillMessageSize.getText();
	}
	
	public String getRESTServerPortList() {
		return textFieldRESTSvrPortList.getText();
	}
	
	public String getRESTReplyWaitTime() {
		return textFieldRESTRRWaitTime.getText();
	}
	
	public String getRESTLocalIPList() {
		return textFieldRESTLocalIPList.getText();
	}
	
	public boolean isXMLAttachments() {
		return chckbxXmlAttachments.isSelected();
	}
	
	public boolean isCallbackOnReactorThread() {
		return chckbxCallbackOnReactor.isSelected();
	}
	public boolean clientsAreEnabled() {
		
		for (SwingWorker<Void, Void> worker : ClientWorkers.values()) {
		    StateValue state = worker.getState();
		    if (state == SwingWorker.StateValue.STARTED) {
		    	// don't need to check them all, first one we find is good enough
		    	return true;
		    }
		}
		return false;
	}
	
	public boolean isShowReply() {
		return chckbxShowReply.isSelected();
	}

	public void enableSingleClientModeBox(boolean b) {
		chckbxEnableSingleClient.setEnabled(b);
	}

	private void terminateAllClients() {
		// we are closing the window so hard kill any outstanding processes (REST clients that have failed to connect can be hard to kill!)
		for (SDKPerfControl control : sdkPerfControls.values()) {
		    control.killProcess();
		}	
	}
	private void changeFont() {
		currentFont = new Font((String)comboBoxFont.getSelectedItem(), Font.PLAIN, (Integer)comboBoxFontSize.getSelectedItem());
		for (JTextArea value : ClientTextAreas.values()) {
		    value.setFont(currentFont);
		}
	}

	public String getRetryCount() {
		return txtRetryCount.getText();
	}

}
