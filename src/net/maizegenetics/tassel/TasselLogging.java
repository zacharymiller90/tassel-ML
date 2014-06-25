/*
 *  TasselLogging
 */
package net.maizegenetics.tassel;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import net.maizegenetics.plugindef.AbstractPlugin;
import net.maizegenetics.plugindef.DataSet;
import net.maizegenetics.prefs.TasselPrefs;
import net.maizegenetics.util.LoggingUtils;

/**
 *
 * @author Terry Casstevens
 */
public class TasselLogging extends AbstractPlugin {

    private static TasselLogging myInstance = null;

    private final JDialog myDialog = new JDialog((Window) null, "Tassel Logging", Dialog.ModalityType.MODELESS);
    private final JTextArea myTextArea = new JTextArea();
    private final TextAreaOutputStream myTextAreaOutputStream = new TextAreaOutputStream(myTextArea);
    private final PrintStream myPrintStream = new PrintStream(myTextAreaOutputStream);

    private TasselLogging(Frame parentFrame) {
        super(parentFrame, true);
        createDialog();
    }

    public static TasselLogging getInstance(Frame parentFrame) {
        if (myInstance == null) {
            myInstance = new TasselLogging(parentFrame);
        }
        return myInstance;
    }

    public static void closeInstance() {
        if (myInstance != null) {
            myInstance.close();
        }
    }

    private void close() {
        TasselPrefs.putLogXDim(myDialog.getWidth());
        TasselPrefs.putLogYDim(myDialog.getHeight());
        myDialog.setVisible(false);
        LoggingUtils.setupLogging();
    }

    private void createDialog() {

        myDialog.setLayout(new BorderLayout());
        int x = TasselPrefs.getLogXDim();
        int y = TasselPrefs.getLogYDim();
        if ((x < 50) || (y < 50)) {
            myDialog.setSize(500, 400);
        } else {
            myDialog.setSize(x, y);
        }

        myTextArea.setLineWrap(true);
        myTextArea.setMargin(new Insets(10, 10, 10, 10));
        myTextArea.setEditable(false);

        final JCheckBox isDebug = new JCheckBox("Debug Level");
        isDebug.setSelected(TasselPrefs.getLogDebug());
        isDebug.setToolTipText("Set to show Debug Logging Messages");
        isDebug.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                boolean debugMode = isDebug.isSelected();
                isDebug.setSelected(debugMode);
                TasselPrefs.putLogDebug(debugMode);
                LoggingUtils.setupLogging(myPrintStream);
            }
        });

        JButton closeButton = new JButton();
        closeButton.setActionCommand("Close");
        closeButton.setText("Close");
        closeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                close();
            }
        });

        JButton clearButton = new JButton();
        clearButton.setActionCommand("Clear");
        clearButton.setText("Clear");
        clearButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                myTextAreaOutputStream.clear();
            }
        });

        JPanel pnlButtons = new JPanel();
        pnlButtons.setLayout(new FlowLayout());
        pnlButtons.add(closeButton);
        pnlButtons.add(clearButton);
        pnlButtons.add(isDebug);

        myDialog.getContentPane().add(new JScrollPane(myTextArea), BorderLayout.CENTER);
        myDialog.getContentPane().add(pnlButtons, BorderLayout.SOUTH);

        myDialog.setResizable(true);

    }

    @Override
    public DataSet processData(DataSet input) {
        LoggingUtils.setupLogging(myPrintStream);
        myDialog.setLocationRelativeTo(getParentFrame());
        myDialog.setVisible(true);
        return null;
    }

    @Override
    public ImageIcon getIcon() {
        URL imageURL = TasselLogging.class.getResource("/net/maizegenetics/tassel/images/log.gif");
        if (imageURL == null) {
            return null;
        } else {
            return new ImageIcon(imageURL);
        }
    }

    @Override
    public String getButtonName() {
        return "Logging";
    }

    @Override
    public String getToolTipText() {
        return "Logging";
    }

    class TextAreaOutputStream extends OutputStream {

        private final byte[] myByteArray = new byte[1];
        private TextAppender myTextAppender;

        public TextAreaOutputStream(JTextArea textArea) {
            myTextAppender = new TextAppender(textArea);
        }

        public synchronized void clear() {
            if (myTextAppender != null) {
                myTextAppender.clear();
            }
        }

        @Override
        public synchronized void close() {
            myTextAppender = null;
        }

        @Override
        public synchronized void flush() {
        }

        @Override
        public synchronized void write(int val) {
            myByteArray[0] = (byte) val;
            write(myByteArray, 0, 1);
        }

        @Override
        public synchronized void write(byte[] ba) {
            write(ba, 0, ba.length);
        }

        @Override
        public synchronized void write(byte[] ba, int str, int len) {
            if (myTextAppender != null) {
                myTextAppender.append(bytesToString(ba, str, len));
            }
        }

    }

    static private String bytesToString(byte[] ba, int str, int len) {
        try {
            return new String(ba, str, len, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return new String(ba, str, len);
        }
    }

    static class TextAppender implements Runnable {

        private static final int MAX_NUM_LINES = 1000;

        private final JTextArea myTextArea;
        private final LinkedList<Integer> myLineLengths = new LinkedList<>();
        private final List<String> myBufferedText = new ArrayList<>();

        private int myCurrentLineLength = 0;
        private boolean myClearAllText = false;
        private boolean myIfNoTextQueued = true;

        TextAppender(JTextArea textArea) {
            myTextArea = textArea;
        }

        synchronized void append(String val) {
            myBufferedText.add(val);
            if (myIfNoTextQueued) {
                myIfNoTextQueued = false;
                EventQueue.invokeLater(this);
            }
        }

        synchronized void clear() {
            myClearAllText = true;
            myCurrentLineLength = 0;
            myLineLengths.clear();
            myBufferedText.clear();
            if (myIfNoTextQueued) {
                myIfNoTextQueued = false;
                EventQueue.invokeLater(this);
            }
        }

        @Override
        public synchronized void run() {
            if (myClearAllText) {
                myTextArea.setText(null);
            }
            for (String val : myBufferedText) {
                myCurrentLineLength += val.length();
                if (val.endsWith(END_OF_LINE) || val.endsWith(SYSTEM_END_OF_LINE)) {
                    if (myLineLengths.size() >= MAX_NUM_LINES) {
                        myTextArea.replaceRange("", 0, myLineLengths.removeFirst());
                    }
                    myLineLengths.addLast(myCurrentLineLength);
                    myCurrentLineLength = 0;
                }
                myTextArea.append(val);
            }
            myBufferedText.clear();
            myClearAllText = false;
            myIfNoTextQueued = true;
        }

        static private final String END_OF_LINE = "\n";
        static private final String SYSTEM_END_OF_LINE = System.getProperty("line.separator", END_OF_LINE);
    }

}
