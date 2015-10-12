/*
 *  Copyright 2006-2015 WebPKI.org (http://webpki.org).
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.webpki.tapconnect;

import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Toolkit;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowAdapter;

import java.io.IOException;

import java.lang.reflect.Field;

import java.net.URL;

import java.util.LinkedHashMap;
import java.util.Timer;
import java.util.TimerTask;

import java.util.logging.Logger;
import java.util.logging.Level;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import javax.swing.border.EmptyBorder;

import javax.swing.plaf.metal.MetalButtonUI;

import org.webpki.json.JSONAlgorithmPreferences;
import org.webpki.json.JSONSignatureDecoder;
import org.webpki.json.JSONObjectReader;
import org.webpki.json.JSONObjectWriter;
import org.webpki.json.JSONParser;

import org.webpki.util.ArrayUtil;

import org.webpki.w2nb.webpayment.common.AccountDescriptor;
import org.webpki.w2nb.webpayment.common.BaseProperties;
import org.webpki.w2nb.webpayment.common.PayerAuthorization;
import org.webpki.w2nb.webpayment.common.AuthorizationData;
import org.webpki.w2nb.webpayment.common.Messages;
import org.webpki.w2nb.webpayment.common.PaymentRequest;
import org.webpki.w2nb.webpayment.common.Encryption;

import org.webpki.w2nbproxy.BrowserWindow;
import org.webpki.w2nbproxy.ExtensionPositioning;
import org.webpki.w2nbproxy.StdinJSONPipe;
import org.webpki.w2nbproxy.StdoutJSONPipe;
import org.webpki.w2nbproxy.LoggerConfiguration;

//////////////////////////////////////////////////////////////////////////
// Web2Native Bridge NFC Launcher                                       //
//                                                                      //
// Note: This is not a product. It is an advanced prototype intended    //
// for testing and showing how a secure NFC/BLE (or WiFi) connection    //
// between a mobile phone based "Wallet" and a merchant payment page    //
// can be architected.                                                  //                  
//                                                                      //
//////////////////////////////////////////////////////////////////////////

public class NFCLauncher {

    static Logger logger = Logger.getLogger("log");

    static StdinJSONPipe stdin = new StdinJSONPipe();
    static StdoutJSONPipe stdout = new StdoutJSONPipe();

    static JDialog frame;
    static Dimension screenDimension;

    static String domainName;

    static final String TOOLTIP_CANCEL         = "Click if you want to abort this operation";
    static final String TOOLTIP_NFC            = "This is the NFC logo...";
    
    static final String VIEW_WAITING           = "WAIT";
    static final String VIEW_NFC               = "NFC";

    static final String BUTTON_CANCEL          = "Cancel";

    static final int TIMEOUT_FOR_REQUEST       = 10000;
    
    static class ScalingIcon extends ImageIcon {
 
        private static final long serialVersionUID = 1L;

        public ScalingIcon(byte[] byteIcon) {
            super(byteIcon);
        }
       
        @Override
        public synchronized void paintIcon(Component c, Graphics g, int x, int y) {
            Image image = getImage();
            int width = image.getWidth(c);
            int height = image.getHeight(c);
            final Graphics2D g2d = (Graphics2D)g.create(x, y, width, height);
            g2d.scale(0.5, 0.5);
            g2d.drawImage(image, 0, 0, c);
            g2d.scale(1, 1);
            g2d.dispose();
        }

        @Override
        public int getIconHeight() {
            return super.getIconHeight() / 2;
        }

        @Override
        public int getIconWidth() {
            return super.getIconWidth() / 2;
        }
    }

    static void terminate() {
        System.exit(3);
    }

    static class JButtonSlave extends JButton {
        
        private static final long serialVersionUID = 1L;

        JButton master;
        
        public JButtonSlave(String text, JButton master) {
            super(text);
            this.master = master;
        }
        
        @Override
        public Dimension getPreferredSize() {
            Dimension dimension = super.getPreferredSize();
            if (master != null) {
                return adjustSize(dimension, master.getPreferredSize());
            } else {
                return dimension;
            }
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension dimension = super.getMinimumSize();
            if (master != null) {
                return adjustSize(dimension, master.getMinimumSize());
            } else {
                return dimension;
            }
        }

        @Override
        public Dimension getSize() {
            Dimension dimension = super.getSize();
            if (master != null) {
                return adjustSize(dimension, master.getSize());
            } else {
                return dimension;
            }
        }

        Dimension adjustSize(Dimension dimension, Dimension masterDimension) {
            if (masterDimension == null ||
                dimension == null ||
                dimension.width > masterDimension.width) {
                return dimension;
            } else {
                return masterDimension;
            }
        }
    }

    static class ApplicationWindow extends Thread {
        Container views;
        JLabel waitingText;
        boolean running = true;
        Font standardFont;
        Font cardNumberFont;
        int fontSize;
        JButton authorizationCancelButton;  // Used as a master for creating unified button widths
        ImageIcon dummyCardIcon;
        boolean macOS;
        boolean retinaFlag;
        boolean hiResImages;

        PaymentRequest paymentRequest;
        
        JSONObjectWriter resultMessage;
        
        ApplicationWindow() throws IOException {
            // First we measure all the panes to be used to get the size of the holding window
            views = frame.getContentPane();
            views.setLayout(new CardLayout());
            int screenResolution = Toolkit.getDefaultToolkit().getScreenResolution();
            fontSize = screenResolution / 7;
            Font font = new JLabel("Dummy").getFont();
            macOS = System.getProperty("os.name").toLowerCase().contains("mac");
            if (font.getSize() > fontSize || macOS) {
                fontSize = font.getSize();
            }
            retinaFlag = isRetina ();
            hiResImages = retinaFlag || fontSize >= 20;
            standardFont = new Font(font.getFontName(), font.getStyle(), fontSize);
            cardNumberFont = new Font("Courier", 
                                      hiResImages ? Font.PLAIN : Font.BOLD,
                                      (fontSize * 4) / 5);
            logger.info("Display Data: Screen resolution=" + screenResolution +
                        ", Screen size=" + screenDimension +
                        ", Font size=" + font.getSize() +
                        ", Adjusted font size=" + fontSize +
                        ", Retina=" + retinaFlag);
            dummyCardIcon = getImageIcon("dummycard.png", false);

            // The initial card showing we are waiting
            initWaitingView();
 
            // For measuring purposes so far
            initNFCConnectView();
        }

        JButton createCardButton (ImageIcon cardIcon, String toolTip) {
            JButton cardButton = new JButton(cardIcon);
            cardButton.setUI(new MetalButtonUI());
            cardButton.setPressedIcon(cardIcon);
            cardButton.setFocusPainted(false);
            cardButton.setMargin(new Insets(0, 0, 0, 0));
            cardButton.setContentAreaFilled(false);
            cardButton.setBorderPainted(false);
            cardButton.setOpaque(false);
            cardButton.setToolTipText(toolTip);
            return cardButton;
        }

        void insertSpacer(JPanel cardSelectionViewCore, int x, int y) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = x;
            c.gridy = y;
            c.gridheight = 2;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1.0;
            cardSelectionViewCore.add(new JLabel(), c);
        }

        JPanel initCardSelectionViewCore() {
            JPanel cardSelectionViewCore = new JPanel();
            cardSelectionViewCore.setBackground(Color.WHITE);
            cardSelectionViewCore.setLayout(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            int itemNumber = 0;
            for (final Integer keyHandle : cards.keySet()) {
                Account card = cards.get(keyHandle);
                boolean even = itemNumber % 2 == 0;
                c.gridx = even ? 1 : 3;
                c.gridy = (itemNumber / 2) * 2;
                insertSpacer(cardSelectionViewCore, c.gridx - 1, c.gridy);
                c.insets = new Insets(c.gridy == 0 ? 0 : fontSize,
                                      0,
                                      0,
                                      0);
                JButton cardImage = createCardButton(card.cardIcon, TOOLTIP_CARD_SELECTION);
                cardImage.setCursor(new Cursor(Cursor.HAND_CURSOR));
                cardImage.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        showAuthorizationView(keyHandle);
                    }
                });
                cardSelectionViewCore.add(cardImage, c);

                c.gridy++;
                c.insets = new Insets(0,
                                      0,
                                      0,
                                      0);
                JLabel accountId = new JLabel(formatAccountId(card), JLabel.CENTER);
                accountId.setFont(cardNumberFont);
                cardSelectionViewCore.add(accountId, c);
                if (!even) {
                    insertSpacer(cardSelectionViewCore, c.gridx + 1, c.gridy - 1);
                }
                itemNumber++;
            }
            return cardSelectionViewCore;
        }

        void initNFCConnectView() throws IOException {
            JPanel cardSelectionView = new JPanel();
            cardSelectionView.setBackground(Color.WHITE);
            cardSelectionView.setLayout(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();

            JLabel headerText = new JLabel("Select Card:");
            headerText.setFont(standardFont);
            c.insets = new Insets(fontSize / 2, fontSize, fontSize, fontSize);
            c.anchor = GridBagConstraints.WEST;
            cardSelectionView.add(headerText, c);

            c.gridx = 0;
            c.gridy = 1;
            c.anchor = GridBagConstraints.CENTER;
            c.fill = GridBagConstraints.BOTH;
            c.weightx = 1.0;
            c.weighty = 1.0; 
            c.insets = new Insets(0, 0, 0, 0);
            if (actualCards) {
                JScrollPane scrollPane = new JScrollPane(initCardSelectionViewCore(cardCollection));
                scrollPane.setBorder(new EmptyBorder(0, 0, 0, 0));
                cardSelectionView.add(scrollPane, c);
            } else {
                LinkedHashMap<Integer,Account> cards = new LinkedHashMap<Integer,Account>();
                for (int i = 0; i < 2; i++) {
                    cards.put(i, new Account(new AccountDescriptor("n/a", DUMMY_ACCOUNT_ID),
                                             true, dummyCardIcon, null, null));
                }
                cardSelectionView.add(initCardSelectionViewCore(cards), c);
            }

            JButtonSlave cancelButton = new JButtonSlave(BUTTON_CANCEL, authorizationCancelButton);
            cancelButton.setFont(standardFont);
            cancelButton.setToolTipText(TOOLTIP_CANCEL);
            cancelButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
            cancelButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    terminate();
                }
            });
            c.gridx = 0;
            c.gridy = 2;
            c.anchor = GridBagConstraints.SOUTHWEST;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0.0;
            c.weighty = 0.0; 
            c.insets = new Insets(fontSize, fontSize, fontSize, fontSize);
            cardSelectionView.add(cancelButton, c);
           
            views.add(cardSelectionView, actualCards ? VIEW_NFC : VIEW_DUMMY_SELECTION);
        }

        void showNFCConnectView() throws IOException {
            ((CardLayout)views.getLayout()).show(views, VIEW_NFC);
        }

        void initWaitingView() {
            JPanel waitingView = new JPanel();
            waitingView.setLayout(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            JLabel waitingIconHolder = getImageLabel("working.gif");
            waitingView.add(waitingIconHolder, c);

            waitingText = new JLabel("Initializing - Please wait");
            waitingText.setFont(standardFont);
            c.gridy = 1;
            c.insets = new Insets(fontSize, 0, 0, 0);
            waitingView.add(waitingText, c);

            views.add(waitingView, VIEW_WAITING);
        }

        void showProblemDialog (boolean error, String message, final WindowAdapter windowAdapter) {
            final JDialog dialog = new JDialog(frame, error ? "Error" : "Warning", true);
            Container pane = dialog.getContentPane();
            pane.setLayout(new GridBagLayout());
            pane.setBackground(Color.WHITE);
            GridBagConstraints c = new GridBagConstraints();
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(fontSize, fontSize * 2, fontSize, fontSize * 2);
            pane.add(getImageLabel(error ? "error.png" : "warning.png"), c);
            JLabel errorLabel = new JLabel(message);
            errorLabel.setFont(standardFont);
            c.anchor = GridBagConstraints.CENTER;
            c.insets = new Insets(0, fontSize * 2, 0, fontSize * 2);
            c.gridy = 1;
            pane.add(errorLabel, c);
            JButtonSlave okButton = new JButtonSlave(BUTTON_OK, authorizationCancelButton);
            okButton.setFont(standardFont);
            c.insets = new Insets(fontSize, fontSize * 2, fontSize, fontSize * 2);
            c.gridy = 2;
            pane.add(okButton, c);
            dialog.setResizable(false);
            dialog.pack();
            dialog.setAlwaysOnTop(true);
            dialog.setLocationRelativeTo(null);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.addWindowListener(windowAdapter);
            okButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    dialog.setVisible(false);
                    windowAdapter.windowClosing(null);
                }
            });
            dialog.setVisible(true);
        }

        void terminatingError(String error) {
            showProblemDialog(true, error, new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    terminate();
                }
            });
        }

        boolean isRetina() {
            if (macOS) {
                GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
                final GraphicsDevice device = env.getDefaultScreenDevice();
                try {
                    Field field = device.getClass().getDeclaredField("scale");
                    if (field != null) {
                        field.setAccessible(true);
                        Object scale = field.get(device);
                        if (scale instanceof Integer && ((Integer)scale).intValue() == 2) {
                            return true;
                        }
                    }
                } catch (Exception ignore) {}
            }
            return false;
        }

        ImageIcon getImageIcon(byte[] byteIcon, boolean animated) {
            try {
                if (retinaFlag || (!hiResImages && animated)) {
                    return new ScalingIcon(byteIcon);
                }
                ImageIcon imageIcon = new ImageIcon(byteIcon);
                if (hiResImages) {
                    return imageIcon;
                }
                int width = imageIcon.getIconWidth() / 2;
                int height = imageIcon.getIconHeight() / 2;
                return new ImageIcon(imageIcon.getImage().getScaledInstance(
                               width == 0 ? 1 : width,
                               height == 0 ? 1 : height,
                               Image.SCALE_SMOOTH));
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed converting image", e);
                terminate();
                return null;
            }
        }

        ImageIcon getImageIcon(String name, boolean animated) {
            try {
                return getImageIcon(ArrayUtil.getByteArrayFromInputStream(
                        getClass().getResourceAsStream (name)), animated);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed reading image", e);
                terminate();
                return null;
            }
        }

        JLabel getImageLabel(String name) {
            return new JLabel(getImageIcon(name, name.endsWith(".gif")));
        }

        @Override
        public void run() {
//TODO NFC
            final Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (running) {
                        running = false;
                        logger.log(Level.SEVERE, "Timeout!");
                        terminatingError("Payment request timeout!");
                    }
                }
            }, TIMEOUT_FOR_REQUEST);
            try {
                JSONObjectReader invokeMessage = stdin.readJSONObject();
                logger.info("Received from browser:\n" + invokeMessage);
                timer.cancel();
                if (running) {
                    // Swing is rather poor for multi-threading...
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            running = false;
                            try {
                                showNFCConnectView();
                            } catch (IOException e) {
                                logger.log(Level.SEVERE, "Stopped", e);
                                terminatingError("Failed, see log file!");
                            }
                        }
                    });
                }
            } catch (IOException e) {
                if (running) {
                    running = false;
                    logger.log(Level.SEVERE, "Undecodable message:\n" + stdin.getJSONString(), e);
                    terminatingError("Undecodable message, see log file!");
                } else {
                    terminate();
                }
            }
            // Catching the disconnect...returns success to proxy
            try {
                stdin.readJSONObject();
            } catch (IOException e) {
                System.exit(0);
            }
        }

    }

    public static void main(String[] args) throws IOException {
        // Configure the logger with handler and formatter
        LoggerConfiguration.init(logger, args);
        for (int i = 0; i < args.length; i++) {
            logger.info("ARG[" + i + "]=" + args[i]);
        }

        // To get the crypto support needed 
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
        
        // Read the calling window information provided by W2NB
        BrowserWindow browserWindow = null;
        ExtensionPositioning extensionPositioning = null;
        try {
            browserWindow = new BrowserWindow(args[3]);
            extensionPositioning = new ExtensionPositioning(args[4]);
            logger.info("Browser window: " + browserWindow);
            logger.info("Positioning arguments: " + extensionPositioning);
            if (args[2].startsWith("http")) {
                domainName = new URL(args[2]).getHost();
            } else {
                domainName = args[2];
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "nativeConnect argument errors", e);
            terminate();
        }

        // Note that Swing returns native precision
        screenDimension = Toolkit.getDefaultToolkit().getScreenSize();

        // Do all the difficult layout stuff
        frame = new JDialog(new JFrame(), "Payment Request [" + domainName + "]");
        ApplicationWindow md = new ApplicationWindow();
        frame.setResizable(false);
        frame.pack();

        ////////////////////////////////////////////////////////////////
        // Positioning: Calculate coordinates in Browser resolution
        ////////////////////////////////////////////////////////////////

        // Note that Swing returns native precision
        Dimension extensionWindow = frame.getSize();
        logger.info("Frame=" + extensionWindow);

        // We need to know the difference between Browser/Native precision
        double factor = screenDimension.height / browserWindow.screenHeight;

        // Browser border size
        double gutter = (browserWindow.outerWidth - browserWindow.innerWidth) / 2;

        // The browser window's position (modulo fixed "chrome") on the screen
        double left = browserWindow.x + gutter;
        double top = browserWindow.y + browserWindow.outerHeight - browserWindow.innerHeight - gutter;
        double width = browserWindow.innerWidth;
        double height = browserWindow.innerHeight;

        // We may rather be targeting a specific HTML element on the invoking page
        if (extensionPositioning.targetRectangle != null) {
            left += extensionPositioning.targetRectangle.left;
            top += extensionPositioning.targetRectangle.top;
            width = extensionPositioning.targetRectangle.width;
            height = extensionPositioning.targetRectangle.height;
        }

        // Position the Wallet on the screen according to the caller's preferences.
        double extWidth = extensionWindow.getWidth() / factor;
        if (extensionPositioning.horizontalAlignment == ExtensionPositioning.HORIZONTAL_ALIGNMENT.Center) {
            left += (width - extWidth) / 2;
        } else if (extensionPositioning.horizontalAlignment == ExtensionPositioning.HORIZONTAL_ALIGNMENT.Right) {
            left += width - extWidth;
        }
        double extHeight = extensionWindow.getHeight() / factor; 
        if (extensionPositioning.verticalAlignment == ExtensionPositioning.VERTICAL_ALIGNMENT.Center) {
            top += (height - extHeight) / 2;
        } else if (extensionPositioning.verticalAlignment == ExtensionPositioning.VERTICAL_ALIGNMENT.Bottom) {
            top += height - extHeight;
        }
        frame.setLocation((int)(left * factor), (int)(top * factor));

        // Respond to caller to indicate that we are (almost) ready for action.
        // We provide the Wallet's width and height data which can be used by the
        // calling Web application to update the page for the Wallet to make
        // it more look like a Web application.  Note that this measurement
        // lacks the 'px' part; you have to add it in the Web application.
        try {
            JSONObjectWriter readyMessage = Messages.createBaseMessage(Messages.WALLET_INITIALIZED)
                .setObject(BaseProperties.WINDOW_JSON, new JSONObjectWriter()
                    .setDouble(BaseProperties.WIDTH_JSON, extWidth)
                    .setDouble(BaseProperties.HEIGHT_JSON, extHeight));
            logger.info("Sent to browser:\n" + readyMessage);
            stdout.writeJSONObject(readyMessage);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error writing to browser", e);
            terminate();
        }

        // In the absence of a genuine window handle (from Chrome) to the caller
        // we put the wallet on top of everything...
        frame.setAlwaysOnTop(true);

        frame.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                terminate();
            }
        });

        // Show the "Waiting" window
        frame.setVisible(true);

        // Start reading and processing the payment request that should be
        // waiting for us at this stage.
        md.start();
    }
}
