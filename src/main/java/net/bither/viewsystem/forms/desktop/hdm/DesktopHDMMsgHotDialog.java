/*
 *
 *  Copyright 2014 http://Bither.net
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */

package net.bither.viewsystem.forms.desktop.hdm;

import com.github.sarxos.webcam.Webcam;
import net.bither.bitherj.core.*;
import net.bither.bitherj.crypto.ECKey;
import net.bither.bitherj.crypto.SecureCharSequence;
import net.bither.bitherj.crypto.TransactionSignature;
import net.bither.bitherj.qrcode.QRCodeUtil;
import net.bither.bitherj.utils.Utils;
import net.bither.qrcode.DesktopQRCodReceive;
import net.bither.qrcode.DesktopQRCodSend;
import net.bither.runnable.CommitTransactionThread;
import net.bither.runnable.CompleteTransactionRunnable;
import net.bither.utils.FileUtil;
import net.bither.utils.WalletUtils;
import net.bither.viewsystem.dialogs.AbstractDesktopHDMMsgDialog;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DesktopHDMMsgHotDialog extends AbstractDesktopHDMMsgDialog {


    static {
        WalletUtils.initTxBuilderException();
    }

    private static final long CHECK_TX_INTERVAL = 3 * 1000;
    private Tx tx;

//    private List<HashMap<String, Long>> addressAmtList = new ArrayList<HashMap<String, Long>>();
    private HashMap<String, Long> sendingRequest = null;
//    private File addressAmtFile;
    private SecureCharSequence password;
    private DesktopHDMKeychain desktopHDMKeychain;


    public DesktopHDMMsgHotDialog(SecureCharSequence password, Webcam webcam) {
        super(webcam);
        isSendMode = true;
        this.password = password;
        desktopHDMKeychain = AddressManager.getInstance().getDesktopHDMKeychains().get(0);
    }

    @Override
    public void handleScanResult(final String result) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (isSendMode) {
                    if (desktopQRCodSend != null) {

                        if (DesktopQRCodSend.getSendCodeFromMsg(result) > desktopQRCodSend.getSendCode()) {
                            if (desktopQRCodSend.sendFinish()) {
                                isSendMode = false;
                                desktopQRCodReceive = new DesktopQRCodReceive();

                            }
                        } else {
                            if (desktopQRCodSend != null) {
                                desktopQRCodSend.setReceiveMsg(result);
                            }
                            if (desktopQRCodSend.canNextPage()) {
                                desktopQRCodSend.nextPage();
                                showQRCode(desktopQRCodSend.getShowMessage());

                            }
                        }
                    }
                } else {
                    if (desktopQRCodReceive != null) {
                        desktopQRCodReceive.receiveMsg(result);
                        showQRCode(desktopQRCodReceive.getShowMsg());
                        if (desktopQRCodSend.sendFinish() && desktopQRCodReceive.receiveComplete()) {
                            publishTx();

                        }
                    }
                }

            }
        });

    }

    public void publishTx() {
        String signStr = desktopQRCodReceive.getReceiveResult();
        String[] signs = QRCodeUtil.splitString(signStr);
        final List<TransactionSignature> transactionSignatureList = new ArrayList<TransactionSignature>();
        for (String str : signs) {
            byte[] bytes = Utils.hexStringToByteArray(str);
            TransactionSignature transactionSignature = new TransactionSignature(ECKey
                    .ECDSASignature.decodeFromDER(bytes), TransactionSignature.SigHash
                    .ALL, false);
            transactionSignatureList.add(transactionSignature);
        }
        final List<DesktopHDMAddress> desktopHDMAddresses = desktopHDMKeychain.getSigningAddressesForInputs(tx.getIns());
        List<byte[]> unSignHash = new ArrayList<byte[]>();
        List<DesktopHDMAddress> unSignDesktopHDMAddress = new ArrayList<DesktopHDMAddress>();
        for (int i = 0; i < desktopHDMAddresses.size(); i++) {
            DesktopHDMAddress a = desktopHDMAddresses.get(i);
            for (byte[] h : tx.getUnsignedInHashesForDesktpHDM(a.getRedeem(), i)) {
                unSignHash.add(h);
                unSignDesktopHDMAddress.add(a);
            }
        }
        //    System.out.println("unSign:" + Utils.bytesToHexString(unSignHash.get(0)));
        desktopHDMKeychain.signTx(tx, unSignHash, password, unSignDesktopHDMAddress, new DesktopHDMKeychain.DesktopHDMFetchOtherSignatureDelegate() {
            @Override
            public List<TransactionSignature> getOtherSignature(Tx tx, List<byte[]> unsignHash, List<AbstractHD.PathTypeIndex> pathTypeIndexLsit) {
                return transactionSignatureList;
            }
        });
        if (!tx.verifySignatures()) {
            System.out.println("tx verify failed");
            return;
        }
        try {
            CommitTransactionThread commitTransactionThread = new CommitTransactionThread(null, tx, false, new CommitTransactionThread.CommitTransactionListener() {
                @Override
                public void onCommitTransactionSuccess(Tx tx) {
//                    synchronized (addressAmtList) {
//                        isSendMode = true;
//                        if (addressAmtList.size() > 0) {
//                            addressAmtList.remove(0);
//                            saveFile(addressAmtList, addressAmtFile);
//                        }

                    if (sendingRequest != null) {
                        desktopHDMKeychain.getSendRequestList().remove(sendingRequest);
                    }
                    desktopQRCodReceive = null;
                    desktopQRCodSend = null;
//                    }
                }

                @Override
                public void onCommitTransactionFailed() {

                }
            });
            commitTransactionThread.start();
        } catch (Exception e) {
            e.printStackTrace();
        }


    }

    @Override
    protected void inited() {
        refreshTx();
    }

    private void refreshTx() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning) {
                    try {
                        if (desktopQRCodSend == null) {
                            try {
                                getTx();
                                SwingUtilities.invokeLater(new Runnable() {
                                    public void run() {
                                        labMsg.setText("");
                                    }
                                });
                            } catch (Exception e) {
                                e.printStackTrace();
                                final String msg = CompleteTransactionRunnable.getMessageFromException(e);
                                SwingUtilities.invokeLater(new Runnable() {
                                    public void run() {
                                        labMsg.setText(msg);
                                    }
                                });
                            }
                        }
                        Thread.sleep(CHECK_TX_INTERVAL);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

            }
        }).start();
    }

    private void getTx() throws Exception {

        if (desktopQRCodSend != null) {
            return;
        }
//        if (addressAmtList.size() == 0) {
//            addressAmtFile = getSendBitcoinFile();
//
//            addressAmtList = getAddressAndAmts(addressAmtFile);
//        }
        String address = null;
        long amt;

        while (this.desktopHDMKeychain.getSendRequestList().size() > 0) {
            HashMap<String, Long> hashMap = this.desktopHDMKeychain.getSendRequestList().peek();
            sendingRequest = hashMap;
//            for (HashMap<String, Long> hashMap : addressAmtList) {
            for (Map.Entry<String, Long> kv : hashMap.entrySet()) {
                address = kv.getKey();
                amt = kv.getValue();
                String changeAddress = desktopHDMKeychain.getNewChangeAddress();
                tx = desktopHDMKeychain.newTx(address, amt);

                List<DesktopHDMAddress> signingAddresses = desktopHDMKeychain.getSigningAddressesForInputs(tx.getIns());
                isSendMode = true;
                desktopQRCodSend = new DesktopQRCodSend(tx, signingAddresses, changeAddress);
                showQRCode(desktopQRCodSend.getShowMessage());
                return;
            }
//            }
        }
    }

    private void saveFile(List<HashMap<String, Long>> list, File file) {
        try {
            String result = "";
            for (HashMap<String, Long> hashMap : list) {
                for (Map.Entry<String, Long> kv : hashMap.entrySet()) {
                    result = result + kv.getKey() + "," + Long.toString(kv.getValue()) + "\n";
                }
            }
            if (file.exists()) {
                file.delete();
            }
            if (list.size() > 0) {
                Utils.writeFile(result.getBytes(), file);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

//    private List<HashMap<String, Long>> getAddressAndAmts(File file) {
//        if (file != null) {
//            String content = Utils.readFile(file);
//            if (Utils.isEmpty(content)) {
//                if (file.exists()) {
//                    file.delete();
//                }
//            }
//            String[] addrssAndAmts = content.split("\n");
//            if (addrssAndAmts.length == 0) {
//                if (file.exists()) {
//                    file.delete();
//                }
//            }
//            for (String str : addrssAndAmts) {
//                String[] temp = str.split(",");
//                if (temp.length > 1) {
//                    if (Utils.validBicoinAddress(temp[0])) {
//                        HashMap<String, Long> hashMap = new HashMap<String, Long>();
//                        hashMap.put(temp[0], Long.valueOf(temp[1]));
//                        addressAmtList.add(hashMap);
//                    }
//                }
//            }
//            if (addressAmtList.size() == 0) {
//                if (file.exists()) {
//                    file.delete();
//                }
//            }
//
//        }
//        return addressAmtList;
//
//
//    }

    private File getSendBitcoinFile() {
        File file = FileUtil.getSendBitcoinDir();
        File[] files = file.listFiles();
        if (files != null && files.length > 0) {
            return files[0];
        } else {
            return null;
        }
    }

}