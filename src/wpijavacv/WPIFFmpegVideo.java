package wpijavacv;

import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_core.IplImage;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.OpenCVFrameConverter;

/**
 * A class used to gather images from the robot's camera.
 * @author Joe Grinstead and Greg Granito
 */
public class WPIFFmpegVideo extends WPIDisposable {

    private FFmpegFrameGrabber grabber;
    private IplImage image;
    private boolean readImage = true;
    private boolean badConnection = false;
    private final Object imageLock = new Object();
    private final Object grabberLock = new Object();
    OpenCVFrameConverter.ToIplImage converter = new OpenCVFrameConverter.ToIplImage();

    public WPIFFmpegVideo(final String path) {
        new Thread() {

            @Override
            public void run() {
                grabber = new FFmpegFrameGrabber(path);
				grabber.setFrameRate(1.0);
                try {
                    grabber.start();

                    while (!isDisposed()) {
                        try {
                            IplImage newest;
                            synchronized (grabberLock) {
                                if (isDisposed()) {
                                    return;
                                }
                                newest = converter.convert(grabber.grab());
                            }
                            if (isNull(newest)) {
                                synchronized (imageLock) {
                                    badConnection = true;
                                    imageLock.notify();
                                }
                                return;
                            } else {
                                synchronized (imageLock) {
                                    if (image == null) {
                                        image = opencv_core.cvCreateImage(newest.cvSize(), newest.depth(), newest.nChannels());
                                    }
                                    opencv_core.cvCopy(newest, image);
                                    readImage = false;
                                    badConnection = false;
                                    imageLock.notify();
                                }
                            }
                        } catch (Exception ex) {
                            synchronized (imageLock) {
                                badConnection = true;
                                imageLock.notify();
                            }
                            ex.printStackTrace();
                            return;
                        }
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ex) {
                        }
                    }
                } catch (Exception ex) {
                    synchronized (imageLock) {
                        badConnection = true;
                        imageLock.notify();
                    }
                    ex.printStackTrace();
                }
            }
        }.start();
    }

    public WPIImage getImage() throws BadConnectionException {
        validateDisposed();

        synchronized (imageLock) {
            if (badConnection) {
                throw new BadConnectionException();
            } else if (image == null) {
                return null;
            } else if (image.nChannels() == 1) {
                return new WPIGrayscaleImage(image.clone());
            } else {
                assert image.nChannels() == 3;
                return new WPIColorImage(image.clone());
            }
        }
    }

    public WPIImage getNewImage(double timeout) throws BadConnectionException {
        validateDisposed();

        synchronized (imageLock) {
            readImage = true;
            while (readImage && !badConnection) {
                try {
                    badConnection = true;
                    imageLock.wait((long) (timeout * 1000));
                } catch (InterruptedException ex) {
                }
            }
            readImage = true;

            if (badConnection) {
                throw new BadConnectionException();
            } else if (image.nChannels() == 1) {
                return new WPIGrayscaleImage(image.clone());
            } else {
                assert image.nChannels() == 3;
                return new WPIColorImage(image.clone());
            }
        }
    }

    public WPIImage getNewImage() throws BadConnectionException {
        return getNewImage(0);
    }

    @Override
    protected void disposed() {
        try {
            synchronized (imageLock) {
                if (!isNull(image)) {
                    image.release();
                }
                image = null;
            }
        } catch (Exception ex) {
        }
    }

    /**
     * An exception that occurs when the camera can not be reached.
     * @author Greg Granito
     */
    public static class BadConnectionException extends Exception {
    }

    @Override
    protected void finalize() throws Throwable {
        grabber.stop();
        super.finalize();
    }
}
