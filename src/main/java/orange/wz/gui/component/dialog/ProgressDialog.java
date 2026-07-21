package orange.wz.gui.component.dialog;

import orange.wz.gui.MainFrame;

import javax.swing.*;
import java.awt.*;

/**
 * 模态进度对话框：用于文件保存等耗时操作期间阻塞用户操作并提示进度。
 * 进度条为不确定模式（转圈动画），消息文案可通过 setMessage 在后台任务推进时动态更新。
 * 保存过程未完成前不可关闭（DO_NOTHING_ON_CLOSE），由调用方在任务结束后 dispose。
 */
public final class ProgressDialog extends JDialog {
    private final JLabel messageLabel;

    public ProgressDialog(String title, String message) {
        super(MainFrame.getInstance(), title, true);
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));

        messageLabel = new JLabel(message);
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);

        panel.add(messageLabel, BorderLayout.CENTER);
        panel.add(progressBar, BorderLayout.SOUTH);

        setContentPane(panel);
        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        setResizable(false);
        pack();
        setLocationRelativeTo(getOwner());
    }

    public void setMessage(String message) {
        messageLabel.setText(message);
    }
}
