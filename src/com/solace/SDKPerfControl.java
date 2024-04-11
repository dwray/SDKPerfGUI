package com.solace;

import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Properties;

import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingWorker;
import net.miginfocom.swing.MigLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.JLabel;
import java.awt.SystemColor;
import javax.swing.SwingConstants;
import javax.swing.JFormattedTextField;
import javax.swing.border.LineBorder;

import com.solacesystems.pubsub.sdkperf.RemoteSDKP;

public class SDKPerfControl extends JPanel {

	/**
	 * @author David Wray, Solace Systems
	 *
	 */
	private static final long serialVersionUID = 1L;
	private boolean bStarted = false;
	
	private String HighSpeedSendRate;
	private String LowSpeedSendRate;
	private String HighSpeedRcvRate;
	private String LowSpeedRcvRate;
	private long ProgressAmount;
	// used to update receive counter in single client mode
	private long Paired_ProgressAmount;

	//TODO change argument names if RMI works...
	public void setProgressAmount(long increment, long paired_increment) {
//		ProgressAmount += increment;
//		if (sdkPerfGUIApp.isSingleClientMode()) {
//			// update receive counter in single client mode
//			Paired_ProgressAmount += paired_increment;
//		}
		if (increment > 0) {
			ProgressAmount = increment;
		}
		if (sdkPerfGUIApp.isSingleClientMode()) {
			// update receive counter in single client mode
			if (paired_increment > 0) {
				Paired_ProgressAmount = paired_increment;
			}
		}
	}
	
	private Process SDKPerfProcess;
	private boolean bPublisher;
	private boolean bSubscriber;
	private boolean bDebug=false;
	private String jarPath;
	JButton jbtnStart;
	JComboBox<String> cmbxSpeed;
	JComboBox<String> cmbxPersistenceType;
	
	private Task task;
	private boolean bConnected = false;
	private String ControlName;
	private JFormattedTextField msgCountTextBox;
	private JLabel lblMsgCount;
	private JCheckBox chckbxShowData;
	private JTextField msgSizeField;
	private JLabel lblSize;
	private SDKPerfGUIApp sdkPerfGUIApp = null;
	// paired subscriber is used just to get subscriber settings in single client mode, it's not actually invoked
	private SDKPerfControl pairedSubscriber;
	private final String LF = System.getProperty("line.separator");
	private ComboBoxModel<String> persistenceModel = new DefaultComboBoxModel<String>(new String[] {"Direct", "Persistent"});
	private ComboBoxModel<String> qosModel = new DefaultComboBoxModel<String>(new String[] {"QoS 0", "QoS 1", "QoS 2"});
	private ComboBoxModel<String> speedModel = new DefaultComboBoxModel<String>(new String[] {"Fast", "Slow"});
	private String summaryText;
	private boolean bAutomatedChange;
	
	public void changePersistenceModel(boolean isQoS) {
		if (isQoS) {
			cmbxPersistenceType.setModel(qosModel);
		} else {
			cmbxPersistenceType.setModel(persistenceModel);
		}
	}
	
	public boolean isFast() {
		boolean bFast = true;
		String speedSelected = (String)cmbxSpeed.getSelectedItem();
		if (speedSelected.contentEquals("Slow")) {
			return false;
		}
		return bFast;
	}
	
	public boolean isShowData() {
		return chckbxShowData.isSelected();
	}

	
	class Task extends SwingWorker<Void, Void> {
		/*
		 * Main task. Executed in background thread.
		 */
		@Override
		public Void doInBackground() {
			// wait until connected
			while (!bConnected && SDKPerfProcess.isAlive()) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException Ignore) {
					//  Ignore
				}
			}
			jbtnStart.setText("Stop");
			
			while (SDKPerfProcess.isAlive()) {
				//Sleep for a short while to avoid burning CPU while waiting for updates
				try {
					Thread.sleep(500);
				} catch (InterruptedException ignore) {
				}

				//Update message count
				msgCountTextBox.setValue(ProgressAmount);
				if (sdkPerfGUIApp.isSingleClientMode()) {
					pairedSubscriber.msgCountTextBox.setValue(Paired_ProgressAmount);
				}
			}
			SDKPerfProcess = null;
			jbtnStart.setText("Start");
			return null;
		}

		/*
		 * Executed in event dispatching thread
		 */
		@Override
		public void done() {
			bStarted = false;	
			// reset the button and checkboxes in the event of process termination not via the enable button
			if (SDKPerfProcess == null || !SDKPerfProcess.isAlive()) {
				jbtnStart.setText("Start");
			}
			changeControlEnablement(true);
		}
	}
	
	private String getSummaryText() {
		
		return summaryText;
	}
	
	public SDKPerfControl(String CName, boolean isPublisher, SDKPerfGUIApp controller) {
		super();
		sdkPerfGUIApp = controller;
		setBorder(new LineBorder(SystemColor.windowBorder));
		// set the control number - used for sorting out which control displays which output in the output control
		ControlName = CName;
		setPublisher(isPublisher);
		
		// get values from properties file
		Properties prop = new Properties();
		ClassLoader loader = Thread.currentThread().getContextClassLoader();           
		InputStream stream = loader.getResourceAsStream("config.properties");
		try {
			prop.load(stream);
			HighSpeedSendRate = prop.getProperty("HighSpeedSendRate");
			LowSpeedSendRate = prop.getProperty("LowSpeedSendRate");
			HighSpeedRcvRate = prop.getProperty("HighSpeedRcvRate");
			LowSpeedRcvRate = prop.getProperty("LowSpeedRcvRate");
			bDebug = Boolean.parseBoolean(prop.getProperty("debug"));
			stream.close();
		} catch (IOException e1) {
			SDKPerfGUIApp.infoBox(e1.toString(), "Error Starting SDKPerf Process");
		}
		
		// this is used to give the jar path of this jar to the process builder command so it can execute SDKPerf
		String path = SDKPerfControl.class.getProtectionDomain().getCodeSource().getLocation().getPath();
		try {
			jarPath = URLDecoder.decode(path, "UTF-8");
		} catch (UnsupportedEncodingException e1) {
			e1.printStackTrace();
		}
		setLayout(new MigLayout("insets 0", "[]2px[201.00][77.00]", "[]2px[]"));
		
		cmbxPersistenceType = new JComboBox<String>();
		cmbxPersistenceType.setModel(persistenceModel);
		
		// we could change the content later by enabling MQTT, so we want to fix size so the GUI doesn't change around
		cmbxPersistenceType.setMinimumSize(new Dimension(120,30));
		
		jbtnStart = new JButton("Start");
		// fix the button size so the layout doesn't keep changing as the button text changes
		jbtnStart.setMinimumSize(new Dimension(80, 30));
		jbtnStart.setMaximumSize(new Dimension(80, 30));
		jbtnStart.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				String debug;

				if (jbtnStart.getText() == "Stop" || jbtnStart.getText() == "Starting") {
					//disabled stop instance of SKDPerf
					bStarted = false;
					jbtnStart.setText("Stopping");
					jbtnStart.repaint();
					StopProcess();
					stopMsgCounter();
					// re-enable other buttons/fields
					changeControlEnablement(true);
					//display appropriate tab
					sdkPerfGUIApp.setOutputDisplayTab(ControlName,bPublisher);
				} else if (jbtnStart.getText() == "Start"){
					// validate all inputs 
					jbtnStart.setText("Starting");
					jbtnStart.repaint();
					String errorString = validateAllInputs();
					if (errorString.length() > 0) {
						//display errors and exit
						SDKPerfGUIApp.infoBox(errorString,"Argument Validation Error");
						jbtnStart.setText("Start");
						return;
					}
					//enabled start instance of SDKPerf
					bStarted = true;
					// shouldn't be able to get here with a running process but still
					killProcess();
					if (!sdkPerfGUIApp.isBaseRMIPortChecked()) {
						createRMIRegistry();
					}
					StartProcess();
					launchSDKPerf();		
					// disable other buttons
					changeControlEnablement(false);
				}
				// the other 2 values stopping/starting will be ignored to prevent multiple clicks causing problems
			}
		});
		add(jbtnStart, "cell 0 0,aligny center");
		add(cmbxPersistenceType, "flowx,cell 1 0,aligny center");
				
		lblMsgCount = new JLabel("Count");
		add(lblMsgCount, "cell 0 1,alignx center");
		
		msgCountTextBox = new JFormattedTextField(NumberFormat.getIntegerInstance());
		msgCountTextBox.setHorizontalAlignment(SwingConstants.RIGHT);
		msgCountTextBox.setBackground(SystemColor.window);
		msgCountTextBox.setEditable(false);
		msgCountTextBox.setText("0");
		add(msgCountTextBox, "cell 1 1,growx");
		msgCountTextBox.setColumns(10);
		
		cmbxSpeed = new JComboBox<>();
		cmbxSpeed.setMinimumSize(new Dimension(79,30));
		cmbxSpeed.setMaximumSize(new Dimension(79,30));
		cmbxSpeed.setModel(speedModel);
		add(cmbxSpeed, "cell 1 0,aligny center");
		
		
		// publishers have message size, subscribers have show payload
		if (bPublisher) {
			lblSize = new JLabel("Size");
			add(lblSize, "cell 2 0,alignx center,aligny center");

			msgSizeField = new JTextField("10");
			msgSizeField.setColumns(8);
			msgSizeField.setMinimumSize(new Dimension(80, 20));
			msgSizeField.setHorizontalAlignment(SwingConstants.LEFT);

			add(msgSizeField, "cell 2 1,growx");
		}else {
			chckbxShowData = new JCheckBox("Show");
			add(chckbxShowData, "cell 2 1,aligny top");			
		}
	}

	public void StopProcess() {
		if (SDKPerfProcess!=null && SDKPerfProcess.isAlive()) {			
			// kill with RMI
			try {
				Registry registry = LocateRegistry.getRegistry(getRMIRegistryPort());
				RemoteSDKP stub = (RemoteSDKP) registry.lookup("RemoteSDKP"+ControlName);
				stub.doRemoteShutdown();
			} catch (java.rmi.UnmarshalException ignore) {
				//since we are doing a shutdown by calling system.exit the sockets close and we get an UnmarshallException - since it's a void method there is no return so it's safe to ignore this
				// terminate the old fashioned way...
				killProcess();
			} catch (RemoteException e) {
				e.printStackTrace();
				// terminate the old fashioned way...
				killProcess();
			} catch (NotBoundException e) {
				e.printStackTrace();
				// terminate the old fashioned way...
				killProcess();
			} 
		}
	}
	
	public void killProcess() {
		if (SDKPerfProcess != null) {
			SDKPerfProcess.destroy();
		}
	}
	private void createRMIRegistry() {
		boolean havePort = false;
		int newPort = sdkPerfGUIApp.getCheckedBaseRMIPort();
		// not sure why I get a warning here as the value clearly is used in the loop
		@SuppressWarnings("unused")
		Registry registry;
		while (!havePort) {
//			System.out.println("Checking RMI Registry Port "+newPort);
			try {
	            registry = LocateRegistry.createRegistry(newPort);
			} catch (java.rmi.ConnectException e) {
				newPort+=300;
				continue;				
			} catch (java.rmi.server.ExportException e) {
				newPort+=300;
				continue;				
			}
			catch (RemoteException e) {
				e.printStackTrace();
				continue;
			}
			havePort = true;
		}
		sdkPerfGUIApp.updateBaseRMIPort(newPort);
//		System.out.println("Found open port range starting at: "+getRMIRegistryPort());
	}
	
	protected void StartProcess() {
		//build args
		//common: shell, router, user@vpn
		ArrayList<String> Args  = new ArrayList<String>();
		// set RMI arguments, xmx, etc.
		processCommandArguments(Args);
		
		// add in subscriptions, publish topics etc.
		processStandardClientArguments(Args);
		
		// do rest and mqtt specific things
		if (sdkPerfGUIApp.isREST()) {
			// not sure if these are for publishers, subscribers or both!
			processRESTSettings(Args);
		}
		else if (sdkPerfGUIApp.isMQTT()) {
			processMQTTSettings(Args);
		}
		
		//quiet mode
		if (sdkPerfGUIApp.isQuiet(bPublisher)) {
			Args.add("-q");
		}

		// add latency settings
		if (sdkPerfGUIApp.isEnableLatencyMeasurement()) {
			Args.add("-l");
			// default latency granularity is 0 so we can safely add any value 0 or above
			Args.add("-lg");
			Args.add(sdkPerfGUIApp.getLatencyGranularity());
			if (sdkPerfGUIApp.isPrintLatencyStats()) {
				Args.add("-lat");
			}
			Args.add("-lb");
			Args.add(sdkPerfGUIApp.getLatencyBuckets());
		}

		if (bDebug) {
			SDKPerfGUIApp.infoBox(Args.toString(), "DEBUG");		
		}
		
		//create and launch the process
		try {
			//Apparently using new String [0] is actually more efficient, I still don't like it.
			ProcessBuilder pb = new ProcessBuilder(Args.toArray(new String[Args.size()]));
			
			pb.redirectErrorStream(true);
			
			// set connected property for message count box
			bConnected = false;

			SDKPerfProcess =  pb.start();
			//set up callbacks for connection and amount of messages published
			Method connectedMethod = this.getClass().getMethod("setConnected");
			Method updateProgressMethod = this.getClass().getMethod("setProgressAmount", long.class, long.class);
			
			//create string of arguments for top of text window
			String builtCommand="";
			Iterator<String> iterator = Args.iterator();
			while(iterator.hasNext()) {
				builtCommand += iterator.next() + " ";
			}

			sdkPerfGUIApp.showClientTextStream(SDKPerfProcess, connectedMethod, updateProgressMethod, this, ControlName, bPublisher, builtCommand);

		} catch (Exception e) {
			SDKPerfGUIApp.infoBox(e.toString(), "Error Starting SDKPerf Process");
		}
	}

	private void processCommandArguments(ArrayList<String> Args) {
		Args.add("java");
		// rmi stuff
		Args.add("-Dcom.sun.management.jmxremote");
		String rmiPort = Integer.toString(getRMIPort());
		Args.add("-Dcom.sun.management.jmxremote.port="+rmiPort);
		Args.add("-Dcom.sun.management.jmxremote.authenticate=false");
		Args.add("-Dcom.sun.management.jmxremote.ssl=false");
		Args.add("-Dcom.solace.clientname="+ControlName);
		Args.add("-Dcom.sun.management.jmxremote.registry.port="+getRMIRegistryPort());
		
		// Java JVM stuff
		Args.add("-cp");
		Args.add(jarPath);
		Args.add("-Xms512m");
		Args.add("-Xmx1024m");
		Args.add("com.solacesystems.pubsub.sdkperf.SDKPerf_java_rmi");
	}

	private void processStandardClientArguments(ArrayList<String> Args) {
		{
			// REST & MQTT handle this bit in their own methods
			if (isNotRESTorMQTT()) {
				Args.add("-cip");
				// set router address taking into account MQTT, REST etc.
				setRouterAddress(Args);
				Args.add("-cu");
				Args.add(sdkPerfGUIApp.getUser()+"@"+sdkPerfGUIApp.getVPN());
				if (sdkPerfGUIApp.getPassword().length() > 0) {
					Args.add("-cp");
					Args.add(new String(sdkPerfGUIApp.getPassword()));
				}
				if (sdkPerfGUIApp.isOrderCheck()) {
					Args.add("-oc");
				}
				Args.add("-rc");
				Args.add(sdkPerfGUIApp.getRetryCount());
			}
			
			if (bPublisher){
				if (sdkPerfGUIApp.isTopic(ControlName)) {
					// it seems pql is not valid for MQTT, you need to use ptl and set topic or queue with the QoS setting, so isTopic always returns true for MQTT
					Args.add("-ptl");
				} else {
					Args.add("-pql");
				}				
				Args.add(sdkPerfGUIApp.getMessageDestination(ControlName));
				Args.add("-mn");
				// has been already validated when start button clicked
				Args.add(sdkPerfGUIApp.getMessageCount(isFast()));
				if (sdkPerfGUIApp.isXMLAttachments()) {
					Args.add("-msx");
				} else {
					Args.add("-msa");
				}
				
				// has been already validated when start button clicked
				Args.add(msgSizeField.getText());
				Args.add("-mr");
				if (isFast()) {
					Args.add(sdkPerfGUIApp.getFastPublishSpeed());
				} else {
					Args.add(sdkPerfGUIApp.getSlowPublishSpeed());
				}
				if (sdkPerfGUIApp.isDTO()) {
					Args.add("-pto");
				}
				if (sdkPerfGUIApp.isRequestReply()) {
					// prq is JMS only (although is not rejected with Java), let's use prt for now
					if (isNotRESTorMQTT()) {
						Args.add("-prt");
					} else {
						Args.add("-prs=/TOPIC/"+sdkPerfGUIApp.getMessageDestination(ControlName)+"_reply");
					}
					if (sdkPerfGUIApp.isShowReply()) {
						Args.add("-md");
					}
				}
				if (sdkPerfGUIApp.isOverrides()) {
					String CoSLevel = sdkPerfGUIApp.getCoSLevel(ControlName);
					if (!CoSLevel.contentEquals("1")) {
						Args.add("-cos");
						Args.add(CoSLevel);
					}
				}
			} 
			if (bSubscriber || sdkPerfGUIApp.isSingleClientMode()) { // subscriber or single client mode
				String subscriberControlName = "";
				if (sdkPerfGUIApp.isSingleClientMode()) {
					subscriberControlName = pairedSubscriber.ControlName;
				} else {
					subscriberControlName = ControlName;
				}
				//note browsing only possible if it's a queue, so isTopic also returns false when browser is selected (i.e. it's a queue)
				//Also, REST desn't have subscription topics
				if (!sdkPerfGUIApp.isREST()) {
					if (sdkPerfGUIApp.isTopic(ControlName)) {
						Args.add("-stl");
					} else {
						Args.add("-sql");
					}	
					Args.add(sdkPerfGUIApp.getMessageDestination(subscriberControlName));
				}
				
				// Request Reply
				//TODO May have to disable for REST/MQTT?  Need to check
				if (sdkPerfGUIApp.isReplyMode()) {
					Args.add("-cm=reply");
				}
				// this seems to fail in queue browser mode, not sure why, for now only use if not browsing
				// need to get browser status from paired subscriber if in single client mode.  
				// REST subscribers don't need an endpoint as they just listen on a port
				if (sdkPerfGUIApp.isProvisionEndpoints() &&!sdkPerfGUIApp.isBrowser(subscriberControlName) &&!sdkPerfGUIApp.isREST()) {
					Args.add("-pe");
					// let's give a permission of delete to keep things simple
					Args.add("-pep");
					Args.add("d");
					// set queue exclusivity
					Args.add("-pea");
					if (sdkPerfGUIApp.isProvisionNonExclusive()) {
						Args.add("0");
					} else {
						Args.add("1");
					}
				}
				// if browsing enabled - note browsing only possible if it's a queue, so isTopic returns false when browser is selected (i.e. it's a queue)
				if(sdkPerfGUIApp.isBrowser(subscriberControlName)) {
					Args.add("-qb");
				}
				
				// add callback on reactor thread flag
				if (sdkPerfGUIApp.isCallbackOnReactorThread()) {
					Args.add("-cor");
					// with the cor flag set the client will not shut down until it has consumed all of its buffered messages (could be minutes)
					// unless you add the no subscription remove flag in which case it shuts down as normal
					Args.add("-nsr");
				}
				
				if (bSubscriber) { //native subscriber		
					if (isFast()) {
						// a fast subscriber might still have a non-zero subscriber delay, 0 is the default so safe to always add
						// don't add if 0 though
						if (!sdkPerfGUIApp.getFastSubscriberDelay().contentEquals("0")) {
							Args.add("-sd");
							Args.add(sdkPerfGUIApp.getFastSubscriberDelay());
						}
					}  else {
						// Non-Zero is enforced through validation
						Args.add("-sd");
						Args.add(sdkPerfGUIApp.getSlowSubscriberDelay());
					}
					if (chckbxShowData.isSelected()) {
						Args.add("-md");
					}
				} else { //paired subscriber in Single Client mode, gets client settings from the paired subscriber not from itself				

					if (pairedSubscriber.isFast()) {
						// a fast subscriber might still have a non-zero subscriber delay, 0 is the default so safe to always add
						// don't add if 0 though
						if (!sdkPerfGUIApp.getFastSubscriberDelay().contentEquals("0")) {
							Args.add("-sd");
							Args.add(sdkPerfGUIApp.getFastSubscriberDelay());
						}
					}  else {
						// Non-Zero is enforced through validation
						Args.add("-sd");
						Args.add(sdkPerfGUIApp.getSlowSubscriberDelay());
					}
					// don't need to add quiet mode for subscriber as will be set in the publisher settings
					if (pairedSubscriber.chckbxShowData.isSelected()) {
						Args.add("-md");
					}
				}
			}
			if (!sdkPerfGUIApp.isMQTT()) {
			// it's safe to always add this (unless MQTT) since direct is the default we are just be explicit about it
				Args.add("-mt");
				Args.add(getPersistenceType());
				//Add a temporary topic endpoint if message type is persistent but we are not using queues
				if (getPersistenceType() == "Persistent" && sdkPerfGUIApp.isTopic(ControlName) && isNotRESTorMQTT()) {
					Args.add("-tte");
					Args.add("1");
				}
			}
		}

	}
	
	private String getPersistenceType() {
		String pType = (String)cmbxPersistenceType.getSelectedItem();
		if (sdkPerfGUIApp.isMQTT()) {
			pType = pType.substring(4);
		}
		return pType;
	}

	private void processMQTTSettings(ArrayList<String> Args) {
		// set the api
		Args.add("-api");
		if (sdkPerfGUIApp.isMQTT5()) {
			Args.add("MQTT5");
		} else {
			Args.add("MQTT");
		}
		
		// I think this is OK for publishers and subscribers
		Args.add("-cu");
		Args.add(sdkPerfGUIApp.getUser()+"@"+sdkPerfGUIApp.getVPN());
		if (sdkPerfGUIApp.getPassword().length() > 0) {
			Args.add("-cp");
			Args.add(new String(sdkPerfGUIApp.getPassword()));
		}

		Args.add("-cip");
		// set router address taking into account MQTT, REST etc.
		setRouterAddress(Args);
		//set QoS
		if (bPublisher) {
			Args.add("-mpq");
			Args.add(getPersistenceType());
		} else {
			Args.add("-msq");
			Args.add(getPersistenceType());
			if (getPersistenceType().contentEquals("1")) {
				// don't remove subscriptions for QoS_1 because we'll lose any non-collected messages
				Args.add("-nsr");
			}
		}
		Args.add("-cn");
		Args.add(ControlName);
		// set clean session flag
		Args.add("-mcs");
		Args.add(sdkPerfGUIApp.getMQTTCleanSession());
		
		processMQTTWill(Args);
	}
	
	private boolean isNotRESTorMQTT() {
		boolean isNotRorM = true;
		if (sdkPerfGUIApp.isMQTT() || sdkPerfGUIApp.isREST()) {
			isNotRorM = false;
		}
		return isNotRorM;
	}

	private void setRouterAddress(ArrayList<String> Args) {
		String routerAddress = sdkPerfGUIApp.getRouterAddress();

  		if (sdkPerfGUIApp.isREST() && sdkPerfGUIApp.getRESTPort().length() > 0) {
			if (routerAddress.contains(":")){
				//strip the common port if accidentally still set
				routerAddress = routerAddress.split(":")[0];
			}
			  routerAddress += ":"+sdkPerfGUIApp.getRESTPort();
		}
		if (sdkPerfGUIApp.isMQTT() && sdkPerfGUIApp.getMQTTPort().length() > 0) {
			//strip the common port if accidentally still set
			if (routerAddress.contains(":")){
				routerAddress = routerAddress.split(":")[0];
			}
			routerAddress += ":"+sdkPerfGUIApp.getMQTTPort();
		}
		if (sdkPerfGUIApp.isTLS()){
			routerAddress = "tcps://" + routerAddress;
		}
		Args.add(routerAddress);
	}

	private void processRESTSettings(ArrayList<String> Args) {
		// set the api
		Args.add("-api");
		Args.add("REST");
		
		if (bPublisher) {
			// REST subscribers don't need users etc.
			Args.add("-cu");
			Args.add(sdkPerfGUIApp.getUser()+"@"+sdkPerfGUIApp.getVPN());
			if (sdkPerfGUIApp.getPassword().length() > 0) {
				Args.add("-cp");
				Args.add(new String(sdkPerfGUIApp.getPassword()));
			}
			Args.add("-cip");
			// set router address taking into account MQTT, REST etc.
			setRouterAddress(Args);

			Args.add("-rcm");
			Args.add(sdkPerfGUIApp.getRESTClientMode());
			if (sdkPerfGUIApp.getRESTReplyWaitTime().length() > 0) {
				Args.add("-rrwt");
				Args.add(sdkPerfGUIApp.getRESTReplyWaitTime());
			}
		} else {
			if (sdkPerfGUIApp.getRESTServerPortList().length() > 0) {
				Args.add("-spl");
				Args.add(sdkPerfGUIApp.getRESTServerPortList());
			}
			if (sdkPerfGUIApp.getRESTLocalIPList().length() > 0) {
				Args.add("-ripl");
				Args.add(sdkPerfGUIApp.getRESTLocalIPList());
			}
		}
	}

	private void processMQTTWill(ArrayList<String> Args) {
		if (sdkPerfGUIApp.isMQTTWill()) {
			// Will topic
			Args.add("-mwmt");
			Args.add(sdkPerfGUIApp.getMQTTWillTopic());
			
			// Will message size
			Args.add("-mwms");
			Args.add(sdkPerfGUIApp.getMQTTWillMessageSize());
			
			// will QoS
			Args.add("-mwmq");
			Args.add(sdkPerfGUIApp.getMQTTWillQoS());
			
			// will message retained
			if (sdkPerfGUIApp.isMQTTRetainWillMessage()) {
				Args.add("-mwmr");
			}
		}
	}

	public int getRMIPort() {
		int ClientNumber= 0;
		// Client ports will be baseport+1-6
		
		if (bPublisher) {
			ClientNumber = Integer.parseInt(ControlName.substring(9));
		} else {
			ClientNumber = Integer.parseInt(ControlName.substring(10))+3;
		}
		return ClientNumber+sdkPerfGUIApp.getCheckedBaseRMIPort();
	}
	
	public int getRMIRegistryPort() {	
		// registry port will be base port
		return sdkPerfGUIApp.getCheckedBaseRMIPort();
	}

	public void setConnected() {
		bConnected=true;
	}

	public void setPublisher(boolean b) {
		bPublisher = b;
		bSubscriber = !b;	
	}
	
	private void stopMsgCounter() {
		task.cancel(false);
	}

	private void launchSDKPerf() {	
		// reset message count
		ProgressAmount = 0;		
		if (sdkPerfGUIApp.isSingleClientMode()) {
			Paired_ProgressAmount = 0;
		}
		msgCountTextBox.setValue(0);

		task = new Task();
	    task.execute();
	}

	private void changeControlEnablement(boolean bEnablement) {
		if (bPublisher) {
			msgSizeField.setEnabled(bEnablement);
			lblSize.setEnabled(bEnablement);
			cmbxPersistenceType.setEnabled(bEnablement);
		} else {
			chckbxShowData.setEnabled(bEnablement);
			// code starting to get complex with all the interactions between all the settings!
			// here we need to make sure not to enable the persistence control of a subscriber if we are shutting down due to someone enabling
			// single client mode while clients were already running.
			if (!sdkPerfGUIApp.isSingleClientMode()) {
				cmbxPersistenceType.setEnabled(bEnablement);
			} 
		}
		// we also need to disable or enable remaining controls on the paired subscriber after starting a single client mode process
		if (bPublisher && sdkPerfGUIApp.isSingleClientMode()) {
			pairedSubscriber.pairedSubscriberControlEnablement(bEnablement);
		}
		cmbxSpeed.setEnabled(bEnablement);
		bAutomatedChange = false;
	}
	
	private void pairedSubscriberControlEnablement(boolean bEnablement) {
		cmbxSpeed.setEnabled(bEnablement);
		chckbxShowData.setEnabled(bEnablement);
	}
	
	private String validateAllInputs() {
		String errorString = "";
		errorString = validateConflictingSettings();
		errorString += validateAllFreeTextInputs();
		return errorString;
	}

	private String validateConflictingSettings() {
		String errorString = "";
		if (sdkPerfGUIApp.isMQTT() && sdkPerfGUIApp.isREST()) {
			errorString += "It is not possible to enable REST and MQTT at the same time."+LF;
		}
		return errorString;
	}

	private String validateAllFreeTextInputs() {
		String errorString = "";
		boolean canBeZero = true;
		boolean cannotBeZero = false;

		// validate common fields
/*		Leaving these as place holders, no obvious validation possible
		sdkPerfGUIApp.getRouterAddress();
		sdkPerfGUIApp.getUser();
		sdkPerfGUIApp.getPassword();
		sdkPerfGUIApp.getVPN();
		sdkPerfGUIApp.isOrderCheck();
		sdkPerfGUIApp.isTopic();
*/		
		// Validate common fields or overrides
		if (sdkPerfGUIApp.getMessageDestination(ControlName).length() == 0) {
			String specificClient = "";
			if (sdkPerfGUIApp.isOverrides()) {
				specificClient = " for "+ControlName+" on the Overrides tab";
			}
			errorString += "Please enter a message destination"+specificClient+"."+LF;
		}
		if (!validatePositiveNumericField(sdkPerfGUIApp.getRetryCount(), canBeZero)) {
			errorString += "Please enter a positive (can be zero) retry count (numeric)."+LF;
		}

		// validate publisher specific fields
		if (bPublisher) {
			if (!validatePositiveNumericField(sdkPerfGUIApp.getMessageCount(isFast()), cannotBeZero)) {
				errorString += "Please enter a positive non-zero message count (numeric)."+LF;
			}
					
			if (!validatePositiveNumericField(sdkPerfGUIApp.getFastPublishSpeed(), cannotBeZero))  {
				errorString += "Please enter a positive non-zero publisher fast speed (numeric)."+LF;
			}
			if (!validatePositiveNumericField(sdkPerfGUIApp.getSlowPublishSpeed(), cannotBeZero))  {
				errorString += "Please enter a positive non-zero publisher slow speed (numeric)."+LF;
			}
			// message size field is on the control not the main app
			if (!validatePositiveNumericField(msgSizeField.getText(), cannotBeZero)) {
				errorString += "Please enter a positive non-zero message size (numeric)."+LF;
			}
		} 
		// we need to validate subscriber settings and publisher settings in single client mode
		// (there are no client specific settings to validate here, so no messing around getting values from the paired subscriber
		if (bSubscriber || sdkPerfGUIApp.isSingleClientMode()){
			// validate consumer specific fields
			if (!validatePositiveNumericField(sdkPerfGUIApp.getFastSubscriberDelay(), canBeZero))  {
				errorString += "Please enter a positive (can be zero) fast subscriber delay (numeric)."+LF;
			}
			if (!validatePositiveNumericField(sdkPerfGUIApp.getSlowSubscriberDelay(), cannotBeZero))  {
				errorString += "Please enter a positive non-zero slow subscriber delay (numeric)."+LF;
			}
		}
		
		// validate latency fields if latency is enabled
		if (sdkPerfGUIApp.isEnableLatencyMeasurement()) {
			// no validation for a check box
		//	sdkPerfGUIApp.isPrintLatencyStats();		
			if (!validatePositiveNumericField(sdkPerfGUIApp.getLatencyGranularity(), canBeZero))  {
				errorString += "Please enter a valid (can be zero) Latency Granularity (numeric)."+LF;
			}
		}
		
		// validate REST fields if REST enabled
		if (sdkPerfGUIApp.isREST()) {
			if (bPublisher) {
				if (!validatePositiveNumericField(sdkPerfGUIApp.getRESTPort(), cannotBeZero)) {
					errorString += "Please enter a valid REST port number (numeric)."+LF;				
				}
			}
			if (bSubscriber) {
				if (!validateNumericList(sdkPerfGUIApp.getRESTServerPortList(), cannotBeZero)) {
					errorString += "Please enter a valid REST Server port list (numeric, non-zero, one or more ports comma separated)."+LF;				
				}
			}
			if (sdkPerfGUIApp.getRESTReplyWaitTime().length() > 0) {
				if (!validatePositiveNumericField(sdkPerfGUIApp.getRESTReplyWaitTime(), canBeZero)) {
					errorString += "Please enter a valid REST Request Reply Wait Time (numeric)."+LF;				
				}
			}
		}
		
		// validate MQTT fields if MQTT enabled
		if (sdkPerfGUIApp.isMQTT()) {
			if (!validatePositiveNumericField(sdkPerfGUIApp.getMQTTPort(), cannotBeZero)) {
				errorString += "Please enter a valid non-zero MQTT port number (numeric)."+LF;				
			}
			// validate MQTT Will Fields if enabled
			if (sdkPerfGUIApp.isMQTTWill()) {
				// no obvious validation for Topic
				//	sdkPerfGUIApp.getMQTTWillTopic()
			
				// Will message size
				if (!validatePositiveNumericField(sdkPerfGUIApp.getMQTTWillMessageSize(), canBeZero)) {
					errorString += "Please enter a valid MQTT Will Message Size (numeric)."+LF;				
				}
			}

		}
		
		return errorString;
	}

	private boolean validateNumericList(String List, boolean canBeZero) {
		//Hmmm...
		boolean result = true;
		int count = 0;
		String Elements[] = List.split(",");
		for (String Element: Elements) {
			if (!validatePositiveNumericField(Element, canBeZero)) {
				return false;
			}
			count++;
		}
		if (count == 0) {
			return false;
		}
		return result;
	}

	private boolean validatePositiveNumericField(String testString, boolean canBeZero) {
		long longTest;
		try {
			longTest = Long.parseLong(testString);
			if (longTest < 1 && !canBeZero) {
				return false;
			} else if (longTest < 0) {
				return false;
			}
		} catch (NumberFormatException nfe) {
			return false;
		}
		return true;
	}

	public void addPairedSubscriber(SDKPerfControl subscriber) {
		// paired subscriber is used just to get subscriber settings in single client mode
		pairedSubscriber = subscriber;	
	}

	public void callSetEnableOnControlButtons(boolean b) {
		jbtnStart.setEnabled(b);
		cmbxPersistenceType.setEnabled(b);
	}
}
