package com.solace;

import javax.swing.JOptionPane;
import com.seaglasslookandfeel.*;

import java.awt.EventQueue;
import javax.swing.JFrame;
import javax.swing.JToggleButton;
import javax.swing.SpinnerDateModel;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.JTextField;
import javax.swing.JLabel;

import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.JTextArea;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.SystemColor;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;
import javax.swing.text.DefaultCaret;

import com.solace.SDKPerfControl.Task;

import java.awt.GridLayout;
import javax.swing.JPasswordField;
import net.miginfocom.swing.MigLayout;
import javax.swing.SwingConstants;
import javax.swing.JRadioButton;
import javax.swing.JButton;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JMenu;
import javax.swing.JSpinner;

public class SDKPerfGUIApp {

	/**
	 * @author David Wray, Solace Systems
	 *
	 */
	private JFrame frmSdkperfGui;
	private JTextField Destination;
	private JTextField Address;
	private int publishersCount = 0;
	private int subscribersCount = 0;
	private HashMap<String,JTextArea> ClientTextAreas = new HashMap<String, JTextArea>();
	private HashMap<String,SwingWorker<Void,Void>> ClientWorkers = new HashMap<String, SwingWorker<Void, Void>>();
	private JTabbedPane tabbedPanePublishers;
	private JTabbedPane tabbedPaneSubscribers;
	// Hit max no swing threads with all the updating controls and tab panes so need my own bigger thread pool 
	private int nSwingWorkerThreads = 20; 
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
				boolean bConnected = false;
				JTextArea textArea = null;
				String LF = System.getProperty("line.separator");
				//currently text areas hardcoded and added manually and have associated other objects not checking if they are added
				
				JTabbedPane tabbedPane = null;
				
				// unfortunately  publisher and subscriber do not have the same number of letters
				// work out client number
				int ClientNumber= 0;
				
				if (bPublisher) {
					tabbedPane = tabbedPanePublishers;
					ClientNumber = Integer.parseInt(ClientName.substring(9)) - 1;
				} else {
					tabbedPane = tabbedPaneSubscribers;
					ClientNumber = Integer.parseInt(ClientName.substring(10)) - 1;
				}
				
				// switch to the appropriate pane
				tabbedPane.setSelectedIndex(ClientNumber);				

				textArea = ClientTextAreas.get(ClientName);
			    // reset the text area
				textArea.setText("Running sdkperf as: "+LF+builtCommand+LF+LF);
				
				JScrollPane scrollPaneClient = (JScrollPane) tabbedPane.getComponentAt(ClientNumber);

				try{
					InputStreamReader isr = new InputStreamReader(sDKPerfProcess.getInputStream());
				    BufferedReader ClientReader = new BufferedReader(isr);
					String line = null;
					int Progress=0;
					int PairedProgress = 0;
					try {
		                while ((line = ClientReader.readLine()) != null && !isCancelled()){
		                	textArea.append(line+LF);	
		                	if (isAutoScroll) {
		        				scrollPaneClient.getVerticalScrollBar().setValue(scrollPaneClient.getVerticalScrollBar().getMaximum());
		                	} 
							if (line.contains("Getting ready to start clients") && !bConnected) {
								bConnected = true;
								//invoke connected method
								ConnectedCallback.invoke(sdkPerfControl);
							}
							// parse line to get amount of messages published
							if (line.contains("PUB MR(") && bConnected) {
								String[] progressLine = line.split(",");
								String[] subLine = null;
								if(bPublisher) {
									subLine = progressLine[0].split("=");
								} else {
									subLine = progressLine[1].split("=");
								}
								Progress = Integer.parseInt(subLine[1].trim());
								if (isEnableLatencyMeasurement()) {
									subLine = progressLine[1].split("=");
									PairedProgress = Integer.parseInt(subLine[1].trim());
								}

								ProgressCallback.invoke(sdkPerfControl, Progress, PairedProgress);
							}
						}
		        		ClientReader.close();
		        		isr.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}catch(Exception e1){}
				return null;
			}};
		threadPool.submit(Worker);
		ClientWorkers.put(ClientName, Worker);
	}
	
	public String getRouterAddress() {
		return Address.getText();
	}

	public String getMessageDestination() {
		return Destination.getText();
	}

	private String RouterAddress="192.168.86.129";
	private String MessageDestination="topic/TopicDemo";
	private boolean isAutoScroll = false;
	
	private boolean isTopic = true;
	public boolean isTopic() {
		return isTopic;
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
			Date time = (Date)timeSpinner.getValue();
			long timeDifference = time.getTime() - baseDate.getTime();
			int speed=0;
			if (fast) {
			   speed = Integer.parseInt(getFastPublishSpeed());
			} else {
				speed = Integer.parseInt(getSlowPublishSpeed());
			}
			long count = speed * (timeDifference/1000);
			return Long.toString(count);
		} else {
			return txtMessageCount.getText();
		}
	}

	public String getLatencyGranularity() {
		return txtLatencyGranularity.getText();
	}

	public boolean isEnableLatencyMeasurement() {
		return chckbxEnableLatencyMeasurement.isSelected();
	}

	public boolean isPrintLatencyStats() {
		return chckbxPrintStats.isSelected();
	}

	public boolean isOrderCheck() {
		return chckbxOrderCheck.isSelected();
	}

	private JTextField txtFastPublishSpeed;
	private JTextField txtSlowPublishSpeed;
	private JTextField txtFastSubscriberDelay;
	private JTextField txtSlowSubscriberDelay;
	private JTextField txtMessageCount;
	private JLabel lblLatencyGranularity;
	private JTextField txtLatencyGranularity;
	private JCheckBox chckbxEnableLatencyMeasurement;
	private JCheckBox chckbxPrintStats;
	private JCheckBox chckbxOrderCheck;
	private JCheckBox chckbxEnableTimer;
	private JSpinner timeSpinner;
	private Date baseDate;

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
		System.out.println("starting");
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

					window.frmSdkperfGui.setMinimumSize(new Dimension(687,535));
					window.frmSdkperfGui.pack();
					window.frmSdkperfGui.setLocationRelativeTo(null);
					window.frmSdkperfGui.setResizable(true);
					window.frmSdkperfGui.setVisible(true);
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
		initialize();
	}

	/**
	 * Initialise the contents of the frame.
	 */
	private void initialize() {
		frmSdkperfGui = new JFrame();
		frmSdkperfGui.setTitle("SDKPerf GUI");
		frmSdkperfGui.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frmSdkperfGui.getContentPane().setLayout(new MigLayout("insets 4px", "[115.00px][198.00px][294.00px,grow]", "[159.00px][16px][54.00px][53.00px][54.00px][25.00][129px,grow][129px,grow]"));
		
		JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
		frmSdkperfGui.getContentPane().add(tabbedPane, "cell 0 0 3 1");
		
		//SDKPerfControls
		//ugh don't like space padding the text fields for alignment, but for some reason I can't stop the subscribers grid square from growing and thus moving the text
		//TODO try to fix this
		JLabel lblPublishers = new JLabel("                           Publishers");
		lblPublishers.setHorizontalAlignment(SwingConstants.CENTER);
		frmSdkperfGui.getContentPane().add(lblPublishers, "cell 0 1 2 1");
		
		JLabel lblSubscribers = new JLabel("                          Subscribers");
		lblSubscribers.setHorizontalAlignment(SwingConstants.CENTER);
		frmSdkperfGui.getContentPane().add(lblSubscribers, "cell 2 1");
		
		SDKPerfControl Publisher1 = new SDKPerfControl("Publisher1", true, this);
		frmSdkperfGui.getContentPane().add(Publisher1, "cell 0 2 2 1,alignx left,aligny top");
				
		SDKPerfControl Subscriber1 = new SDKPerfControl("Subscriber1", false, this);
		frmSdkperfGui.getContentPane().add(Subscriber1, "cell 2 2,alignx left,aligny top");
		Publisher1.addPairedSubscriber(Subscriber1);
				
		SDKPerfControl Publisher2 = new SDKPerfControl("Publisher2", true, this);
		frmSdkperfGui.getContentPane().add(Publisher2, "cell 0 3 2 1,alignx left,aligny top");
				
		SDKPerfControl Subscriber2 = new SDKPerfControl("Subscriber2", false, this);
		frmSdkperfGui.getContentPane().add(Subscriber2, "cell 2 3,alignx left,aligny top");
		Publisher2.addPairedSubscriber(Subscriber2);
				
		SDKPerfControl Publisher3 = new SDKPerfControl("Publisher3", true, this);
		frmSdkperfGui.getContentPane().add(Publisher3, "cell 0 4 2 1,alignx left,aligny top");
		
		SDKPerfControl Subscriber3 = new SDKPerfControl("Subscriber3", false, this);
		frmSdkperfGui.getContentPane().add(Subscriber3, "cell 2 4,alignx left,aligny top");
		Publisher3.addPairedSubscriber(Subscriber3);
		
		//Common Setting fields
		JPanel panelCommonSettings = new JPanel();
		tabbedPane.addTab("Common", null, panelCommonSettings, null);
		panelCommonSettings.setBorder(new TitledBorder(null, "Common Settings", TitledBorder.LEADING, TitledBorder.TOP, null, null));
		//		panelCommonSettings.setLayout(new MigLayout("insets 0", "[107.00px][297.00px]0px", "[21px][21px][21px][21px][22px]0px"));
				panelCommonSettings.setLayout(new MigLayout("insets 0", "[]0px[325.00][][89.00]", "[]0px[][][][]"));
				
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
		
		//Advanced Publisher fields
		JPanel panelAdvancedPublisher = new JPanel();
		tabbedPane.addTab("Publisher", null, panelAdvancedPublisher, null);
		panelAdvancedPublisher.setLayout(new MigLayout("", "[][133.00][][23.00][grow][grow]", "[][][][][]"));
		
		JLabel lblFastSpeed = new JLabel("Fast Speed");
		panelAdvancedPublisher.add(lblFastSpeed, "cell 0 0,alignx trailing");
		
		txtFastPublishSpeed = new JTextField();
		txtFastPublishSpeed.setText("1000");
		panelAdvancedPublisher.add(txtFastPublishSpeed, "cell 1 0,growx");
		txtFastPublishSpeed.setColumns(10);
		
		JLabel lblsec = new JLabel("/Sec");
		panelAdvancedPublisher.add(lblsec, "cell 2 0");
		
		JCheckBox chckbxDeliverToOne = new JCheckBox("Deliver To One");
		panelAdvancedPublisher.add(chckbxDeliverToOne, "cell 4 0");
		
		JLabel lblSlowSpeed = new JLabel("Slow Speed");
		panelAdvancedPublisher.add(lblSlowSpeed, "cell 0 1,alignx trailing");
		
		txtSlowPublishSpeed = new JTextField();
		txtSlowPublishSpeed.setText("10");
		panelAdvancedPublisher.add(txtSlowPublishSpeed, "cell 1 1,growx");
		txtSlowPublishSpeed.setColumns(10);
		
		JLabel lblsec_1 = new JLabel("/Sec");
		panelAdvancedPublisher.add(lblsec_1, "cell 2 1");
		
		JCheckBox chckbxRequestreply = new JCheckBox("Request/Reply");
		panelAdvancedPublisher.add(chckbxRequestreply, "cell 4 1");
			
		// Advanced Subscriber Fields
		JPanel panelAdvancedSubscriber = new JPanel();
		tabbedPane.addTab("Subscriber", null, panelAdvancedSubscriber, null);
		panelAdvancedSubscriber.setLayout(new MigLayout("", "[][166.00][grow]", "[][][]"));
		
		JLabel lblFastDelay = new JLabel("Fast Delay ms");
		panelAdvancedSubscriber.add(lblFastDelay, "cell 0 0,alignx trailing");
		
		txtFastSubscriberDelay = new JTextField();
		txtFastSubscriberDelay.setText("0");
		panelAdvancedSubscriber.add(txtFastSubscriberDelay, "cell 1 0,growx");
		txtFastSubscriberDelay.setColumns(10);
		
		JCheckBox chckbxReplyMode = new JCheckBox("Reply Mode");
		panelAdvancedSubscriber.add(chckbxReplyMode, "cell 2 0");
		
		JLabel lblSlowDelay = new JLabel("Slow Delay ms");
		panelAdvancedSubscriber.add(lblSlowDelay, "cell 0 1,alignx trailing");
		
		txtSlowSubscriberDelay = new JTextField();
		txtSlowSubscriberDelay.setText("100");
		panelAdvancedSubscriber.add(txtSlowSubscriberDelay, "cell 1 1,growx");
		txtSlowSubscriberDelay.setColumns(10);
		
		// timer panel controls
		JPanel panelTimed = new JPanel();
		tabbedPane.addTab("Timed", null, panelTimed, null);
		panelTimed.setLayout(new MigLayout("", "[grow]", "[][][][]"));
		
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
		
		// Latency Fields
		JPanel panelLatency = new JPanel();
		tabbedPane.addTab("Latency", null, panelLatency, null);
		panelLatency.setLayout(new MigLayout("", "[][][grow][grow]", "[][][]"));
		
		chckbxEnableLatencyMeasurement = new JCheckBox("Measure Latency");
		chckbxEnableLatencyMeasurement.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (chckbxEnableLatencyMeasurement.isSelected()) {
					//enable the other latency controls
					chckbxPrintStats.setEnabled(true);
					txtLatencyGranularity.setEnabled(true);
					// disable 'enable' buttons on subscriber controls
					Subscriber1.disableEnableButton(true);
					Subscriber2.disableEnableButton(true);
					Subscriber3.disableEnableButton(true);
					// change label text for latency mode
					lblPublishers.setText("Latency Mode: Publish Settings");					
					lblSubscribers.setText("Latency Mode: Subscribe Settings");
					tabbedPaneSubscribers.setEnabled(false);
				} else {
					//disable the other latency controls
					chckbxPrintStats.setEnabled(false);
					txtLatencyGranularity.setEnabled(false);
					Subscriber1.disableEnableButton(false);
					Subscriber2.disableEnableButton(false);
					Subscriber3.disableEnableButton(false);
					// return heading text to normal
					lblPublishers.setText("                           Publishers");					
					lblSubscribers.setText("                          Subscribers");
					tabbedPaneSubscribers.setEnabled(true);
				}
			}
		});
		panelLatency.add(chckbxEnableLatencyMeasurement, "cell 0 0");
		
		chckbxPrintStats = new JCheckBox("Print Stats");
		panelLatency.add(chckbxPrintStats, "cell 1 0");
		
		lblLatencyGranularity = new JLabel();
		lblLatencyGranularity.setText("Latency Granularity");
		panelLatency.add(lblLatencyGranularity, "cell 0 1,alignx center");
		
		txtLatencyGranularity = new JTextField("0");
		panelLatency.add(txtLatencyGranularity, "cell 1 1");
		txtLatencyGranularity.setColumns(10);

		// disable latency controls to start (re-enabled when 'enable latency' is selected
		chckbxPrintStats.setEnabled(false);
		txtLatencyGranularity.setEnabled(false);
		
		JToggleButton tglbtnAutoScroll = new JToggleButton("Auto Scroll");
		tglbtnAutoScroll.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				isAutoScroll = !isAutoScroll;
			}
		});
		tglbtnAutoScroll.setSelected(false);
		frmSdkperfGui.getContentPane().add(tglbtnAutoScroll, "cell 1 5");
				
		tabbedPanePublishers = new JTabbedPane(JTabbedPane.TOP);
		frmSdkperfGui.getContentPane().add(tabbedPanePublishers, "cell 0 6 3 1,grow");

		tabbedPaneSubscribers = new JTabbedPane(JTabbedPane.TOP);
		frmSdkperfGui.getContentPane().add(tabbedPaneSubscribers, "cell 0 7 3 1,grow");

		JToggleButton tglbtnHideOutput = new JToggleButton("Hide Output");
		tglbtnHideOutput.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (tglbtnHideOutput.getSelectedObjects() == null) {
					// show output
					frmSdkperfGui.setResizable(true);
					tabbedPanePublishers.setVisible(true);
					tabbedPaneSubscribers.setVisible(true);
					tglbtnAutoScroll.setVisible(true);
					frmSdkperfGui.pack();
				} else {
					// hide output
					tabbedPanePublishers.setVisible(false);
					frmSdkperfGui.setSize(687,535);
					tabbedPaneSubscribers.setVisible(false);					
					tglbtnAutoScroll.setVisible(false);
					frmSdkperfGui.setResizable(false);
				}
			}
		});
		frmSdkperfGui.getContentPane().add(tglbtnHideOutput, "cell 0 5");
		
		JMenuBar menuBar = new JMenuBar();
		frmSdkperfGui.setJMenuBar(menuBar);
		
		JMenu mnHelp = new JMenu("Help");
		menuBar.add(mnHelp);
		
		JMenuItem mntmNewMenuItem = new JMenuItem("About");
		mnHelp.add(mntmNewMenuItem);
		mntmNewMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				SDKPerfGUIAbout about = new SDKPerfGUIAbout();
				about.setVisible(true);
			}
		});
		
		//add output windows for 3 publishers and 3 subscribers
		addClient(true);
		addClient(true);
		addClient(true);
		addClient(false);
		addClient(false);
		addClient(false);
		tabbedPanePublishers.setSelectedIndex(0);
		tabbedPanePublishers.setSelectedIndex(0);
				
		frmSdkperfGui.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				/// Tidy up any remaining processes if window closed without disabling them
				Publisher1.StopProcess();
				Publisher2.StopProcess();
				Publisher3.StopProcess();
				Subscriber1.StopProcess();
				Subscriber2.StopProcess();
				Subscriber3.StopProcess();
			}
		});

	}
	private void addClient(boolean bPublisher) {
		JTabbedPane tabbedPane = null;
		String ClientName = null;
		if (bPublisher) {
			publishersCount++;
			ClientName = "Publisher"+publishersCount;
			tabbedPane = tabbedPanePublishers;
		} else {
			subscribersCount++;
			ClientName = "Subscriber"+subscribersCount;	
			tabbedPane = tabbedPaneSubscribers;
		}
		JScrollPane scrollPaneClient = new JScrollPane();

		// add text area to map		
		JTextArea textAreaClient = new JTextArea();
		scrollPaneClient.setViewportView(textAreaClient);
		textAreaClient.setBackground(SystemColor.text);
		textAreaClient.setEditable(false);
		ClientTextAreas.put(ClientName, textAreaClient);
		
		tabbedPane.add(ClientName, scrollPaneClient);
		
		return;
	}

}
