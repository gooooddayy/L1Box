package xyz.doikki.videoplayer.controller;

import android.graphics.Bitmap;

public interface MediaPlayerControl {

    void start();

    void pause();

    long getDuration();

    long getCurrentPosition();

    void seekTo(long pos);

    boolean isPlaying();

    int getBufferedPercentage();

    void startFullScreen();

    void stopFullScreen();

    boolean isFullScreen();

    void setMute(boolean isMute);

    boolean isMute();

    void setScreenScaleType(int screenScaleType);

    void setSpeed(float speed);

    float getSpeed();

    long getTcpSpeed();

    void replay(boolean resetPosition);

    void setMirrorRotation(boolean enable);

    Bitmap doScreenShot();

    int[] getVideoSize();

    /**
     * 视频旋转角度（0 / 90 / 180 / 270）。
     *
     * 与 {@link #getVideoSize()} 是**两条独立通道**：宽高是解码器上报的编码尺寸，
     * 旋转只作用于画面渲染、不改写宽高。所以带旋转标记的竖屏片，用 getVideoSize()
     * 读出来的宽高方向与实际画面相反，需要调用方按本角度自行换算。
     */
    int getVideoRotation();

    void setRotation(float rotation);

    void startTinyScreen();

    void stopTinyScreen();

    boolean isTinyScreen();
}