package com.solace;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;

public class RightClickMenu extends MouseAdapter {
	private JPopupMenu popup = new JPopupMenu();
	private Action copyAction;
    private Action selectAllAction;
    private JTextArea textArea;
    
	public RightClickMenu() {
		
       copyAction = new AbstractAction("Copy") {
            @Override
            public void actionPerformed(ActionEvent e) {
            	textArea.copy();
            }

        };
        popup.add(copyAction);
        popup.addSeparator();
        
        selectAllAction = new AbstractAction("Select All") {
            @Override
            public void actionPerformed(ActionEvent e) {
                textArea.selectAll();
            }
        };
        popup.add(selectAllAction);
	}
    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getButton() == 3) {
            if (!(e.getSource() instanceof JTextArea)) {
                return;
            }

            displayPopup(e);
        }
        if (e.getButton() == 1) {
            if (!(e.getSource() instanceof JTextArea)) {
                return;
            }
            textArea = (JTextArea) e.getSource();
            textArea.requestFocusInWindow();
        }
    }
	private void displayPopup(MouseEvent e) {
		// Sometimes the menu pops up and then jumps a bit.  Hopefully this will fix it!
		if (!popup.isVisible()) {
			textArea = (JTextArea) e.getSource();
			textArea.requestFocusInWindow();
	
			boolean enabled = textArea.isEnabled();
			boolean nonempty = !(textArea.getText() == null || textArea.getText().equals(""));
			boolean marked = textArea.getSelectedText() != null;
	
			copyAction.setEnabled(enabled && marked);
			selectAllAction.setEnabled(enabled && nonempty);
	
			int nx = e.getX();
	
			if (nx > 500) {
			    nx = nx - popup.getSize().width;
			}
	
			popup.show(e.getComponent(), nx, e.getY() - popup.getSize().height);
		}
	}
    @Override
    public void mousePressed(MouseEvent e) {
        if (e.getButton() == 3) {
            if (!(e.getSource() instanceof JTextArea)) {
                return;
            }
            displayPopup(e);
        }
    }
}
