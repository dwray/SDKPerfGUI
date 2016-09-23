package com.solace;

import javax.swing.JOptionPane;

import java.awt.EventQueue;
import javax.swing.JFrame;
import javax.swing.JToggleButton;
import javax.swing.SwingWorker;
import javax.swing.JTextField;
import javax.swing.JLabel;

import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import javax.swing.JTextArea;
import java.awt.Color;
import java.awt.SystemColor;
import javax.swing.JScrollPane;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;

import com.solace.SDKPerfControl.Task;

import java.awt.GridLayout;
import javax.swing.JPasswordField;

public class SDKPerfUIApp {

	/**
	 * @author David Wray, Solace Systems
	 *
	 */
	private JFrame frmSdkperfGui;
	// Don't like all these static members and accessors but since the Control is a separate class it's the easiest way to
	// share the latest values of the common variables as they may change after instantiating the Control objects which means passing 
	// them in the constructor isn't going to work.  
	private static JTextField Destination;
	private static JTextField Address;
	private static JTextArea textAreaProducers;
	private static JTextArea textAreaConsumers;
	private static SwingWorker ProducerWorker;
	private static SwingWorker ConsumerWorker;
	
	public static void setProducerTextStream(InputStream stream, Method ConnectedCallback, SDKPerfControl sdkPerfControl) {
		//stop updates from previous stream
		if (ProducerWorker != null) {
			ProducerWorker.cancel(true);
		}
        // clear the text area
		textAreaProducers.setText(null);
		
		ProducerWorker = new SwingWorker() {
			public Void doInBackground() {
				boolean bConnected = false;
				try{
					String LF = System.getProperty("line.separator");
					InputStreamReader isr = new InputStreamReader(stream);
				    BufferedReader ProducersReader = new BufferedReader(isr);
					String line = null;
					try {
		                while ( (line = ProducersReader.readLine()) != null && !isCancelled()){
							textAreaProducers.append(line+LF);		   
							if (line.contains("Getting ready to start clients") && !bConnected) {
								bConnected = true;
								//invoke connected method
								ConnectedCallback.invoke(sdkPerfControl);
							}
						}
		        		ProducersReader.close();
		        		isr.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}catch(Exception e1){}
				return null;
			}};
		ProducerWorker.execute();
	}
	public static void setConsumerTextStream(InputStream stream, Method ConnectedCallback, SDKPerfControl sdkPerfControl) {
		//stop updates from previous stream
		if (ConsumerWorker != null) {
			ConsumerWorker.cancel(true);
		}
		
        // clear the text area
		textAreaConsumers.setText(null);
		ConsumerWorker = new SwingWorker<Void,Void>() {
			public Void doInBackground() {
				boolean bConnected = false;
				try{
					String LF = System.getProperty("line.separator");
					InputStreamReader isr = new InputStreamReader(stream);
					BufferedReader ConsumersReader = new BufferedReader(isr);
					String line = null;
					try {
		                while ( (line = ConsumersReader.readLine()) != null &&!ConsumerWorker.isCancelled()){
							textAreaConsumers.append(line+LF);	
							if (line.contains("Getting ready to start clients") && !bConnected) {
								bConnected = true;
								//invoke connected method
								ConnectedCallback.invoke(sdkPerfControl);
							}
		                }
		                isr.close();
		                ConsumersReader.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}catch(Exception e1){}
				return null;
			}};
			ConsumerWorker.execute();
	}
	public static JTextArea getTextAreaProducers() {
		return textAreaProducers;
	}
	
	public static JTextArea getTextAreaConsumers() {
		return textAreaConsumers;
	}
	
	public static JTextField getRouterAddress() {
		return Address;
	}

	public static JTextField getMessageDestination() {
		return Destination;
	}

	private String RouterAddress="192.168.238.129";
	private String MessageDestination="topic/TopicDemo";
	private static boolean isTopic = true;
	public static boolean isTopic() {
		return isTopic;
	}

	public static JTextField getVPN() {
		return VPN;
	}

	public static JTextField getUser() {
		return User;
	}

	private static JTextField VPN;
	private static JTextField User;
	private static JPasswordField txtPassword;

	public static JPasswordField getPassword(){
		return txtPassword;
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
					SDKPerfUIApp window = new SDKPerfUIApp();
// Don't know why pack() isn't working manually setting window size for now...					
//					window.frmSdkperfGui.pack();
					window.frmSdkperfGui.setSize(460,740);
					window.frmSdkperfGui.setLocationRelativeTo(null);
					window.frmSdkperfGui.setResizable(false);
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
	public SDKPerfUIApp() {
		initialize();
	}

	/**
	 * Initialise the contents of the frame.
	 */
	private void initialize() {
		frmSdkperfGui = new JFrame();
		frmSdkperfGui.setTitle("SDKPerf GUI");
		frmSdkperfGui.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frmSdkperfGui.getContentPane().setLayout(null);
		
		JPanel panel = new JPanel();
		panel.setBorder(new TitledBorder(null, "Common Settings", TitledBorder.LEADING, TitledBorder.TOP, null, null));
		panel.setBounds(21, 16, 422, 134);
		frmSdkperfGui.getContentPane().add(panel);
		panel.setLayout(null);
		
		JLabel lblRouterAddress = new JLabel("Router Address");
		lblRouterAddress.setBounds(7, 18, 136, 21);
		panel.add(lblRouterAddress);
		
		Address = new JTextField();
		Address.setBounds(143, 18, 272, 21);
		panel.add(Address);
		Address.setText(RouterAddress);
		Address.setColumns(10);
		
		JLabel label = new JLabel("");
		label.setBounds(279, 18, 136, 21);
		panel.add(label);
				
		JLabel lblUser = new JLabel("User");
		lblUser.setBounds(7, 39, 136, 21);
		panel.add(lblUser);
		
		User = new JTextField();
		User.setBounds(143, 39, 272, 21);
		panel.add(User);
		User.setText("default");
		User.setColumns(10);
		
		JLabel label_3 = new JLabel("");
		label_3.setBounds(279, 39, 136, 21);
		panel.add(label_3);
		
		JLabel lblPassword = new JLabel("Password");
		lblPassword.setBounds(7, 60, 136, 21);
		panel.add(lblPassword);
		
		txtPassword = new JPasswordField();
		txtPassword.setBounds(143, 60, 272, 21);
		txtPassword.setColumns(10);
		panel.add(txtPassword);
		
		JLabel label_4 = new JLabel("");
		label_4.setBounds(279, 60, 136, 21);
		panel.add(label_4);
		
		JLabel lblVpn = new JLabel("VPN");
		lblVpn.setBounds(7, 81, 136, 21);
		panel.add(lblVpn);
		
		VPN = new JTextField();
		VPN.setBounds(143, 81, 272, 21);
		panel.add(VPN);
		VPN.setText("default");
		VPN.setColumns(10);
		
		JLabel label_5 = new JLabel("");
		label_5.setBounds(279, 81, 136, 21);
		panel.add(label_5);
		
		JLabel lblDestination = new JLabel("Destination");
		lblDestination.setBounds(7, 102, 136, 21);
		panel.add(lblDestination);
		
		Destination = new JTextField();
		Destination.setBounds(143, 102, 222, 21);
		panel.add(Destination);
		Destination.setText(MessageDestination);
		Destination.setColumns(10);
		
		JToggleButton tglbtnTopic = new JToggleButton("Topic");
		tglbtnTopic.setBackground(Color.DARK_GRAY);
		tglbtnTopic.setBounds(364, 103, 47, 21);
		panel.add(tglbtnTopic);
		tglbtnTopic.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				isTopic = !isTopic;
			}
		});
		tglbtnTopic.setSelected(true);
				
		JLabel lblProducers = new JLabel("Producers");
		lblProducers.setBounds(96, 162, 77, 16);
		frmSdkperfGui.getContentPane().add(lblProducers);
		
		JLabel lblConsumers = new JLabel("Consumers");
		lblConsumers.setBounds(305, 162, 88, 16);
		frmSdkperfGui.getContentPane().add(lblConsumers);
		
		SDKPerfControl Producer1 = new SDKPerfControl();
		Producer1.setBounds(21, 190, 206, 51);
		frmSdkperfGui.getContentPane().add(Producer1);
		Producer1.setProducer(true);
				
		SDKPerfControl Consumer1 = new SDKPerfControl();
		Consumer1.setBounds(237, 190, 206, 51);
		frmSdkperfGui.getContentPane().add(Consumer1);
		Consumer1.setProducer(false);
				
		SDKPerfControl Producer2 = new SDKPerfControl();
		Producer2.setBounds(21, 254, 206, 51);
		frmSdkperfGui.getContentPane().add(Producer2);
		Producer2.setProducer(true);
				
		SDKPerfControl Consumer2 = new SDKPerfControl();
		Consumer2.setBounds(237, 253, 206, 51);
		frmSdkperfGui.getContentPane().add(Consumer2);
		Consumer2.setProducer(false);
				
		SDKPerfControl Producer3 = new SDKPerfControl();
		Producer3.setBounds(21, 317, 206, 51);
		frmSdkperfGui.getContentPane().add(Producer3);
		Producer3.setProducer(true);
		
		SDKPerfControl Consumer3 = new SDKPerfControl();
		Consumer3.setLayout(null);
		Consumer3.setBounds(237, 317, 206, 51);
		frmSdkperfGui.getContentPane().add(Consumer3);
		Consumer3.setProducer(false);
		
		JScrollPane scrollPaneProducers = new JScrollPane();
		scrollPaneProducers.setBounds(16, 408, 427, 129);
		frmSdkperfGui.getContentPane().add(scrollPaneProducers);
		
		textAreaProducers = new JTextArea();
		scrollPaneProducers.setViewportView(textAreaProducers);
		textAreaProducers.setBackground(SystemColor.text);
		textAreaProducers.setEditable(false);
		
		JScrollPane scrollPaneConsumers = new JScrollPane();
		scrollPaneConsumers.setBounds(16, 572, 427, 129);
		frmSdkperfGui.getContentPane().add(scrollPaneConsumers);
		
		textAreaConsumers = new JTextArea();
		scrollPaneConsumers.setViewportView(textAreaConsumers);
		textAreaConsumers.setEditable(false);
		textAreaConsumers.setBackground(Color.WHITE);
		
		JLabel lblProducerOutput = new JLabel("Producer Output");
		lblProducerOutput.setBounds(175, 380, 143, 16);
		frmSdkperfGui.getContentPane().add(lblProducerOutput);
		
		JLabel lblConsumerOutput = new JLabel("Consumer Output");
		lblConsumerOutput.setBounds(175, 549, 143, 16);
		frmSdkperfGui.getContentPane().add(lblConsumerOutput);		
		
		JToggleButton tglbtnHideOutput = new JToggleButton("Hide Output");
		tglbtnHideOutput.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (tglbtnHideOutput.getSelectedObjects() == null) {
					// show output
					frmSdkperfGui.setResizable(true);
					frmSdkperfGui.setSize(460,740);
					lblProducerOutput.setVisible(true);
					lblConsumerOutput.setVisible(true);
					textAreaProducers.setVisible(true);
					textAreaConsumers.setVisible(true);
					scrollPaneProducers.setVisible(true);
					scrollPaneConsumers.setVisible(true);
					frmSdkperfGui.setResizable(true);					
				} else {
					// hide output
					frmSdkperfGui.setResizable(true);
					frmSdkperfGui.setSize(460,440);
					lblProducerOutput.setVisible(false);
					lblConsumerOutput.setVisible(false);
					textAreaProducers.setVisible(false);
					textAreaConsumers.setVisible(false);
					scrollPaneProducers.setVisible(false);
					scrollPaneConsumers.setVisible(false);
					frmSdkperfGui.setResizable(false);					
				}
			}
		});
		tglbtnHideOutput.setBounds(21, 375, 106, 29);
		frmSdkperfGui.getContentPane().add(tglbtnHideOutput);
		
		frmSdkperfGui.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				/// Tidy up any remaining processes if window closed without disabling them
				Producer1.StopProcess();
				Producer2.StopProcess();
				Producer3.StopProcess();
				Consumer1.StopProcess();
				Consumer2.StopProcess();
				Consumer3.StopProcess();
			}
		});

	}
}
