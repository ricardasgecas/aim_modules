package org.dobots.cameramodule;

import java.util.HashMap;
import java.util.List;

import org.dobots.aim.AimProtocol;
import org.dobots.aim.AimService;
import org.dobots.aim.AimUtils;
import org.dobots.lib.comm.msg.RoboCommands;
import org.dobots.lib.comm.msg.RoboCommands.BaseCommand;
import org.dobots.lib.comm.msg.RoboCommands.CameraCommand;
import org.dobots.lib.comm.msg.RoboCommands.ControlCommand;
import org.dobots.utilities.CameraPreview;
import org.dobots.utilities.CameraPreview.CameraPreviewCallback;
import org.dobots.utilities.ThreadMessenger;
import org.dobots.utilities.log.AndroidLogger;
import org.dobots.utilities.log.Loggable;
import org.dobots.utilities.log.Logger;
import org.dobots.zmq.video.IRawVideoListener;
import org.dobots.zmq.video.VideoThrottle;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Intent;
import android.hardware.Camera.Size;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;

public class CameraService extends AimService implements IRawVideoListener {

	private static final String TAG = "CameraService";
	private static final String MODULE_NAME = "CameraModule";

	private CameraPreview mCameraPreview;
	private VideoThrottle mVideoThrottle;

	@Override
	public String getModuleName() {
		return MODULE_NAME;
	}

	@Override
	public String getTag() {
		return TAG;
	}

	// a ThreadMessenger is a thread with a messenger. messages are
	// handled by that thread
	// a normal Messenger uses the thread that creates the messenger
	// to handle messages (which in this case would be the main (UI)
	// thread
	private ThreadMessenger mPortCmdInReceiver = new ThreadMessenger("PortCmdInMessenger") {

		@Override
		public boolean handleIncomingMessage(Message msg) {
			switch (msg.what) {
			case AimProtocol.MSG_PORT_DATA:
				// do we need to check datatype to make sure it is string?
				String data = msg.getData().getString("data");
				BaseCommand cmd = RoboCommands.decodeCommand(data);

				if (cmd instanceof ControlCommand) {
					ControlCommand control = (ControlCommand)cmd;
					if (control.mCommand.equals("setFrameRate")) {
						setFrameRate((Double)control.getParameter(0));
					} else if (control.mCommand.equals("setSize")) {
						setPreviewSize((Integer)control.getParameter(0), (Integer)control.getParameter(1));
					}
				} else if (cmd instanceof CameraCommand) {
					CameraCommand camera = (CameraCommand)cmd;
					switch(camera.eType) {
					case TOGGLE:
						mCameraPreview.toggleCamera();
						break;
					case ON:
						mCameraPreview.startCamera();
						break;
					case OFF:
						mCameraPreview.stopCamera();
						break;
					default:
						break;
					}
				}
				break;
			default:
				return false;
			}
			return true;
		}
	};

	public void defineInMessenger(HashMap<String, Messenger> list) {
		list.put("cmd", mPortCmdInReceiver.getMessenger());
	}

	public void defineOutMessenger(HashMap<String, Messenger> list) {
		list.put("video_raw", null);
		list.put("video_base64", null);
		//		list.put("video_yuv", null);
	}

	private IBinder mBinder = new CameraBinder();

	public class CameraBinder extends Binder {

		public CameraService getCamera() {
			return CameraService.this;
		}

	}

	public void setFrameRate(double rate) {
		mVideoThrottle.setFrameRate(rate);
	}

	public double getFrameRate() {
		return mVideoThrottle.getFrameRate();
	}

	public void setPreviewSize(int width, int height) {
		mCameraPreview.setPreviewSize(width, height);
	}

	public Size getPreviewSize() {
		return mCameraPreview.getPreviewSize();
	}

	public List<Size> getSupportedPreviewSizes() {
		return mCameraPreview.getSupportedPreviewSizes();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	@Override
	public void onCreate() {
		super.onCreate();

		Logger.setLogger(new AndroidLogger());

		mCameraPreview = CameraPreview.createCameraWithoutSurface(this);
		mCameraPreview.setPreviewSize(320, 240);

		mVideoThrottle = new VideoThrottle("videoThrottle");
		mVideoThrottle.setRawVideoListener(this);
		mVideoThrottle.setFrameRate(20.0);

		mCameraPreview.setFrameListener(new CameraPreviewCallback() {

			@Override
			public void onFrame(byte[] rgb, int width, int height, int rotation) {
				mVideoThrottle.onFrame(rgb, rotation);
			}
		});
		//		mCameraPreview.setYuvListener(new CameraPreviewCallback() {
		//			@Override
		//			public void onFrame(byte[] rgb, int width, int height, int rotation, int id) {
		//				mVideoThrottle.onFrame(rgb, rotation, id);
		//			}
		//		});
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		mCameraPreview.destroy();
		mVideoThrottle.stopThread();
		mPortCmdInReceiver.destroy();
	}

	@Override
	public void onFrame(byte[] rgb, int rotation) {

		if (getOutMessenger("video_raw") != null) {
			Bundle data = setVideoData(rgb, rotation);
			sendData(getOutMessenger("video_raw"), data);
		}

		if (getOutMessenger("video_base64") != null) {
			String base64 = android.util.Base64.encodeToString(rgb, android.util.Base64.NO_WRAP);

			JSONObject json = new JSONObject();
			try {
				json.put("base64", base64);
				json.put("rotation", rotation);

				sendData(getOutMessenger("video_base64"), json.toString());
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		//		if (getOutMessenger("yuv") != null) {
		//			Bundle data = setVideoData(rgb, rotation);
		//			sendData(getOutMessenger("bmp"), data);
		//		}
	}

	private Bundle setVideoData(byte[] rgb, int rotation) {

		// calculate length
		int length = 1; // 1 field for the number of structures
		length += 4; // 2 fields per structure, 1 for number of dim, 1 for length of dim
		length += rgb.length; // number of rgb elements
		length += 1; // number of rotation elements

		// create aim array
		int[] aimArray = new int[length];

		// fill array
		aimArray[0] = 2; // number of 'structures'

		int index = 1;

		// rgb
		aimArray[index++] = 1; // number of dimensions of 'structure'
		aimArray[index++] = rgb.length; // length of the dimension
		for (int j = 0; j < rgb.length; ++j) {
			aimArray[index++] = rgb[j];
		}

		// rotation
		aimArray[index++] = 1;
		aimArray[index++] = 1;
		aimArray[index++] = rotation;

		Bundle bundle = new Bundle();
		bundle.putInt("datatype", AimProtocol.DATATYPE_INT_ARRAY);
		bundle.putIntArray("data", aimArray);
		return bundle;
	}

}
