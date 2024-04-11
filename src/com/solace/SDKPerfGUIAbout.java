package com.solace;

import java.awt.BorderLayout;
import java.awt.FlowLayout;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import javax.swing.JLabel;
import java.awt.Panel;
import java.awt.image.BufferedImage;
import java.io.IOException;

import net.miginfocom.swing.MigLayout;
import javax.swing.SwingConstants;
import java.awt.Label;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

public class SDKPerfGUIAbout extends JDialog {

	private final JPanel contentPanel = new JPanel();

	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {
		try {
			SDKPerfGUIAbout dialog = new SDKPerfGUIAbout();
			dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
			dialog.setVisible(true);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Create the dialog.
	 */
	public SDKPerfGUIAbout() {
		BufferedImage logoImage;
		try {
			
			logoImage = ImageIO.read(this.getClass().getResource("solace-logo-200px.png"));
			setBounds(100, 100, 450, 300);
			getContentPane().setLayout(new BorderLayout());
			contentPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
			getContentPane().add(contentPanel, BorderLayout.CENTER);
			contentPanel.setLayout(new MigLayout("", "[415.00px][10px]", "[16px][][166.00]"));
			{
				JLabel lblProducedByDavid = new JLabel("Produced by Dr DWray for Solace Labs");
				contentPanel.add(lblProducedByDavid, "cell 0 0,alignx left,aligny top");
			}
			{
				JLabel solaceLogo = new JLabel(new ImageIcon(logoImage));
				contentPanel.add(solaceLogo, "cell 0 2");
			}
			{
				JPanel buttonPane = new JPanel();
				buttonPane.setLayout(new FlowLayout(FlowLayout.RIGHT));
				getContentPane().add(buttonPane, BorderLayout.SOUTH);
				{
					JButton okButton = new JButton("OK");
					okButton.addActionListener(new ActionListener() {
						public void actionPerformed(ActionEvent e) {
							setVisible(false);
						}
					});
					okButton.setActionCommand("OK");
					buttonPane.add(okButton);
					getRootPane().setDefaultButton(okButton);
				}
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
