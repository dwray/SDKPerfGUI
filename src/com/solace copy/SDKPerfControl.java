package com.solace;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Properties;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JToggleButton;
import javax.swing.SwingWorker;
import net.miginfocom.swing.MigLayout;
import javax.swing.JCheckBox;
import javax.swing.JTextField;
import javax.swing.JLabel;
import java.awt.SystemColor;
import javax.swing.SwingConstants;
import javax.swing.JFormattedTextField;
import javax.swing.border.BevelBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EtchedBorder;
import javax.swing.border.LineBorder;
import javax.swing.text.NumberFormatter;
import javax.swing.JSpinner;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class SDKPerfControl extends JPanel {

	/**
	 * @author David Wray, Solace Systems
	 *
	 */
	private static final long serialVersionUID = 1L;
	private boolean bEnabled = false;
	private boolean bFast = true;
	
	private PersistenceType persistenceType = new PersistenceType();
	
	private String HighSpeedSendRate;
	private String LowSpeedSendRate;
	private String HighSpeedRcvRate;
	private String LowSpeedRcvRate;
	private long ProgressAmount;
	private long Paired_ProgressAmount;

	public void setProgressAmount(int increment, int paired_increment) {
		ProgressAmount += increment;
		if (sdkPerfGUIApp.isEnableLatencyMeasurement()) {
			Paired_ProgressAmount += paired_increment;
		}
	}
	
	private Process SDKPerfProcess;
	private boolean bPublisher;
	private boolean bSubscriber;
	private boolean bDebug=false;
	private String jarPath;
	JToggleButton tglbtnEnable;
	JButton jbtnFast;
	JButton jbtnPersistenceType;

	
	private Task task;
	private boolean bConnected = false;
	private String ControlName;
	private int progressBarMax;
	private JFormattedTextField msgCountTextBox;
	private JLabel lblMsgCount;
	private JCheckBox chckbxShowData;
	private JTextField msgSizeField;
	private JLabel lblSize;
	private SDKPerfGUIApp sdkPerfGUIApp = null;
	private SDKPerfControl pairedSubscriber;
	
	public boolean isFast() {
		return bFast;
	}
	
	public boolean isShowData() {
		return chckbxShowData.isSelected();
	}

	class PersistenceType {
		
		private String[] types = {"direct","nonpersistent","persistent"};
		private String[] labels = {"Direct","-Persist","+Persist"};
		private String[] tooltips = {"Use Direct Messaging","Use Non Persistent  Messaging","Use Persistent Messaging"};
		
		private int currentClientMessagingType = 0;
		
		public String getCurrentButtonLabel() {
			return labels[currentClientMessagingType];
		}
		
		public String getNextButtonLabel() {
			if (currentClientMessagingType < 2) { 
				currentClientMessagingType++;
			} else {
				currentClientMessagingType = 0;
			}
			return labels[currentClientMessagingType];
		}
		
		public String getCurrentToolTip() {
			return tooltips[currentClientMessagingType];
		}
		
		public String getCurrentClientMessagingType() {
			return types[currentClientMessagingType];
		}
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
			
			while (SDKPerfProcess.isAlive()) {
				//Sleep for a short while to avoid burning CPU while waiting for updates
				try {
					Thread.sleep(100);
				} catch (InterruptedException ignore) {}

				//Update message count
				msgCountTextBox.setValue(new Long(ProgressAmount));
				if (sdkPerfGUIApp.isEnableLatencyMeasurement()) {
					pairedSubscriber.msgCountTextBox.setValue(new Long(Paired_ProgressAmount));
				}
//				setProgress(progress);
			}
			return null;
		}

		/*
		 * Executed in event dispatching thread
		 */
		@Override
		public void done() {
 			bEnabled = false;	
			// reset the button and checkboxes in the event of process termination not via the enable button
 			changeControlEnablement(true);
			tglbtnEnable.setSelected(false);
		}
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
			// TODO Auto-generated catch block
			SDKPerfGUIApp.infoBox(e1.toString(), "Error Starting SDKPerf Process");
		}
		
		// this is used to give the jar path of this jar to the process builder command so it can execute SDKPerf
		String path = SDKPerfControl.class.getProtectionDomain().getCodeSource().getLocation().getPath();
		try {
			jarPath = URLDecoder.decode(path, "UTF-8");
		} catch (UnsupportedEncodingException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		setLayout(new MigLayout("insets 0", "[]2px[142.00][77.00]", "[]2px[]"));
		
		jbtnPersistenceType = new JButton(persistenceType.getCurrentButtonLabel());
		// fix the button size so the layout doesn't keep changing as the button text changes
		jbtnPersistenceType.setMinimumSize(new Dimension(80, 30));
		jbtnPersistenceType.setMaximumSize(new Dimension(80, 30));
		jbtnPersistenceType.setToolTipText(persistenceType.getCurrentToolTip());
		jbtnPersistenceType.addActionListener(new ActionListener() {
		// cycle through the types: direct, nonpersistent, persistent
		public void actionPerformed(ActionEvent e) {
//				bPersistent = !bPersistent;
				jbtnPersistenceType.setText(persistenceType.getNextButtonLabel());
				jbtnPersistenceType.setToolTipText(persistenceType.getCurrentToolTip());
			}
		});
		
		tglbtnEnable = new JToggleButton("Enable");
		tglbtnEnable.addActionListener(new ActionListener() {

			public void actionPerformed(ActionEvent e) {
				String debug;

				if (tglbtnEnable.getSelectedObjects() == null) {
					//disabled stop instance of SKDPerf
					bEnabled = false;
					StopProcess();
					stopMsgCounter();
					// re-enable other buttons/fields
					changeControlEnablement(true);
				} else {
					// validate all inputs 
					String errorString = validateAllFreeTextInputs();
					if (errorString.length() > 0) {
						//display errors and exit
						SDKPerfGUIApp.infoBox(errorString,"Argument Validation Error");
						tglbtnEnable.setSelected(false);
						return;
					}
					//enabled start instance of SDKPerf
					bEnabled = true;
					// shouldn't be able to get here with a process but still
					StopProcess();
					StartProcess();
					launchSDKPerf();		
					// disable other buttons
					changeControlEnablement(false);
				}
			}

		});
		add(tglbtnEnable, "cell 0 0,aligny center");
		add(jbtnPersistenceType, "flowx,cell 1 0,aligny center");
				
		lblMsgCount = new JLabel("Count");
		add(lblMsgCount, "cell 0 1,alignx center");
		
		msgCountTextBox = new JFormattedTextField(NumberFormat.getIntegerInstance());
		msgCountTextBox.setHorizontalAlignment(SwingConstants.RIGHT);
		msgCountTextBox.setBackground(SystemColor.window);
		msgCountTextBox.setEditable(false);
		msgCountTextBox.setText("0");
		add(msgCountTextBox, "cell 1 1,growx");
		msgCountTextBox.setColumns(10);
		
		jbtnFast = new JButton("Fast");
		jbtnFast.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				bFast = !bFast;
				if (bFast) {
					jbtnFast.setText("Fast");
				} else {
					jbtnFast.setText("Slow");
				}
			}
		});
		add(jbtnFast, "cell 1 0,aligny center");
		
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
		if (SDKPerfProcess != null) {
			// kill nicely for now
			SDKPerfProcess.destroy();


//			SDKPerfProcess.destroyForcibly(); 
//			try {
//				SDKPerfProcess.waitFor();
				SDKPerfProcess = null;
				bConnected = false;
//			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
//				SDKPerfGUIApp.infoBox(e.toString(), "Error Stopping SDKPerf Process");
//			}	
		}
	}
	
	protected void StartProcess() {
		//build args
		//common: shell, router, user@vpn
		ArrayList<String> Args  = new ArrayList<String>();
		Args.add("java");
		Args.add("-cp");
		Args.add(jarPath);
		Args.add("-Xms512m");
		Args.add("-Xmx1024m");
		Args.add("com.solacesystems.pubsub.sdkperf.SDKPerf_java");
		Args.add("-cip");
		Args.add(sdkPerfGUIApp.getRouterAddress());
		Args.add("-cu");
		Args.add(sdkPerfGUIApp.getUser()+"@"+sdkPerfGUIApp.getVPN());
		if (sdkPerfGUIApp.getPassword().length() > 0) {
			Args.add("-cp");
			Args.add(new String(sdkPerfGUIApp.getPassword()));
		}
		if (sdkPerfGUIApp.isOrderCheck()) {
			Args.add("-oc");
		}
		
		if (bPublisher){
			if (sdkPerfGUIApp.isTopic()) {
				Args.add("-ptl");
			} else {
				Args.add("-pql");
			}				
			Args.add(sdkPerfGUIApp.getMessageDestination());
			Args.add("-mn");
			// has been already validated when enable button clicked
			Args.add(sdkPerfGUIApp.getMessageCount(bFast));
			Args.add("-msa");
			// has been already validated when enable button clicked
			Args.add(msgSizeField.getText());
			Args.add("-mr");
			if (bFast) {
				Args.add(sdkPerfGUIApp.getFastPublishSpeed());
			} else {
				Args.add(sdkPerfGUIApp.getSlowPublishSpeed());
				
			}
		} else  { // regular subscriber!
			if (sdkPerfGUIApp.isTopic()) {
				Args.add("-stl");
			} else {
				Args.add("-sql");
			}				
			Args.add(sdkPerfGUIApp.getMessageDestination());
			if (bFast) {
				// a fast subscriber might still have a non-zero subscriber delay, 0 is the default so safe to always add
				Args.add("-sd");
				Args.add(sdkPerfGUIApp.getFastSubscriberDelay());
			}  else {
				Args.add("-sd");
				Args.add(sdkPerfGUIApp.getSlowSubscriberDelay());
			}
			if (chckbxShowData.isSelected()) {
				Args.add("-md");
			}
		}
		// it's safe to always add this since direct is the default we are just be explicit about it
		Args.add("-mt");
		Args.add(persistenceType.getCurrentClientMessagingType());
		
		// add latency settings if enabled
		if (sdkPerfGUIApp.isEnableLatencyMeasurement()) {
			// add subscriber settings but with values from the paired subscriber
			if (sdkPerfGUIApp.isTopic()) {
				Args.add("-stl");
			} else {
				Args.add("-sql");
			}				
			Args.add(sdkPerfGUIApp.getMessageDestination());
			if (pairedSubscriber.isFast()) {
				// a fast subscriber might still have a non-zero subscriber delay, 0 is the default so safe to always add
				Args.add("-sd");
				Args.add(sdkPerfGUIApp.getFastSubscriberDelay());
			}  else {
				Args.add("-sd");
				Args.add(sdkPerfGUIApp.getSlowSubscriberDelay());
			}
			if (pairedSubscriber.isShowData()) {
				Args.add("-md");
			}
			Args.add("-l");
			// default latency granularity is 0 so we can safely add any value 0 or above
			Args.add("-lg");
			Args.add(sdkPerfGUIApp.getLatencyGranularity());
			if (sdkPerfGUIApp.isPrintLatencyStats()) {
				Args.add("-lat");
			}
		}
		
		if (bDebug) {
			SDKPerfGUIApp.infoBox(Args.toString(), "DEBUG");		
		}
		try {
			//Apparently using new String [0] is actually more efficient, I still don't like it.
			ProcessBuilder pb = new ProcessBuilder(Args.toArray(new String[Args.size()]));
			
			pb.redirectErrorStream(true);
			
			// set connected property for progress bar animation
			bConnected = false;

			SDKPerfProcess =  pb.start();
			//set up callbacks for connection and amount of messages published
			Method connectedMethod = this.getClass().getMethod("setConnected");
			Method updateProgressMethod = this.getClass().getMethod("setProgressAmount", int.class, int.class);
			
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

	public void setConnected() {
		bConnected=true;
	}

	public void setPublisher(boolean b) {
		bPublisher = b;
		bSubscriber = !b;	
	}
	
	private void stopMsgCounter() {
		task.cancel(true);
	}

	private void launchSDKPerf() {	
		// reset message count
		ProgressAmount = 0;		
		if (sdkPerfGUIApp.isEnableLatencyMeasurement()) {
			Paired_ProgressAmount = 0;
		}
		msgCountTextBox.setValue(new Long(0));

		task = new Task();
	    task.execute();
	}

	private void changeControlEnablement(boolean bEnablement) {
		if (bPublisher) {
			msgSizeField.setEnabled(bEnablement);
			lblSize.setEnabled(bEnablement);
		} else {
			chckbxShowData.setEnabled(bEnablement);
		}
		jbtnPersistenceType.setEnabled(bEnablement);
		jbtnFast.setEnabled(bEnablement);
	}
	private String validateAllFreeTextInputs() {
		String errorString = "";
		String LF = System.getProperty("line.separator");

		// validate common fields
/*		Leaving these as place holders, no obvious validation possible
		sdkPerfGUIApp.getRouterAddress();
		sdkPerfGUIApp.getUser();
		sdkPerfGUIApp.getPassword();
		sdkPerfGUIApp.getVPN();
		sdkPerfGUIApp.getMessageDestination();
		sdkPerfGUIApp.isOrderCheck();
		sdkPerfGUIApp.isTopic();
*/		
		// validate publisher specific fields
		if (bPublisher) {
			if (!validatePositiveNumericField(sdkPerfGUIApp.getMessageCount(bFast), false)) {
				errorString += "Please enter a positive non-zero message count (numeric)."+LF;
			}
					
			if (!validatePositiveNumericField(sdkPerfGUIApp.getFastPublishSpeed(), false))  {
				errorString += "Please enter a positive non-zero publisher fast speed (numeric)."+LF;
			}
			if (!validatePositiveNumericField(sdkPerfGUIApp.getSlowPublishSpeed(), false))  {
				errorString += "Please enter a positive non-zero publisher slow speed (numeric)."+LF;
			}
			// message size field is on the control not the main app
			if (!validatePositiveNumericField(msgSizeField.getText(), false)) {
				errorString += "Please enter a positive non-zero message size (numeric)."+LF;
			}
		} else {
			// validate consumer specific fields
			if (!validatePositiveNumericField(sdkPerfGUIApp.getFastSubscriberDelay(), true))  {
				errorString += "Please enter a positive (can be zero) fast subscriber delay (numeric)."+LF;
			}
			if (!validatePositiveNumericField(sdkPerfGUIApp.getSlowSubscriberDelay(), false))  {
				errorString += "Please enter a positive non-zero slow subscriber delay (numeric)."+LF;
			}
		}
		
		// validate latency fields if latency is enabled
		if (sdkPerfGUIApp.isEnableLatencyMeasurement()) {
			// no validation for a check box
		//	sdkPerfGUIApp.isPrintLatencyStats();		
			if (!validatePositiveNumericField(sdkPerfGUIApp.getLatencyGranularity(), true))  {
				errorString += "Please enter a valid (can be zero) Latency Granularity (numeric)."+LF;
			}
		}
		return errorString;
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
		pairedSubscriber = subscriber;	
	}

	public void disableEnableButton(boolean b) {
		tglbtnEnable.setEnabled(!b);
//		msgCountTextBox.setVisible(!b);
//		lblMsgCount.setVisible(!b);
		jbtnPersistenceType.setEnabled(!b);
	}
}
