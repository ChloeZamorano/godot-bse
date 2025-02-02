/*************************************************************************/
/*  GodotIO.java                                                         */
/*************************************************************************/
/*                       This file is part of:                           */
/*                           GODOT ENGINE                                */
/*                      https://godotengine.org                          */
/*************************************************************************/
/* Copyright (c) 2007-2021 Juan Linietsky, Ariel Manzur.                 */
/* Copyright (c) 2014-2021 Godot Engine contributors (cf. AUTHORS.md).   */
/*                                                                       */
/* Permission is hereby granted, free of charge, to any person obtaining */
/* a copy of this software and associated documentation files (the       */
/* "Software"), to deal in the Software without restriction, including   */
/* without limitation the rights to use, copy, modify, merge, publish,   */
/* distribute, sublicense, and/or sell copies of the Software, and to    */
/* permit persons to whom the Software is furnished to do so, subject to */
/* the following conditions:                                             */
/*                                                                       */
/* The above copyright notice and this permission notice shall be        */
/* included in all copies or substantial portions of the Software.       */
/*                                                                       */
/* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,       */
/* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF    */
/* MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.*/
/* IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY  */
/* CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,  */
/* TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE     */
/* SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.                */
/*************************************************************************/

package org.godotengine.godot;

import org.godotengine.godot.input.GodotEditText;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.graphics.Point;
import android.media.*;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.WindowInsets;

import java.io.IOException;
import java.util.Locale;

// Wrapper for native library

public class GodotIO {
	private static final String TAG = GodotIO.class.getSimpleName();

	private final AssetManager am;
	private final Activity activity;
	private final String uniqueId;
	GodotEditText edit;

	MediaPlayer mediaPlayer;

	final int SCREEN_LANDSCAPE = 0;
	final int SCREEN_PORTRAIT = 1;
	final int SCREEN_REVERSE_LANDSCAPE = 2;
	final int SCREEN_REVERSE_PORTRAIT = 3;
	final int SCREEN_SENSOR_LANDSCAPE = 4;
	final int SCREEN_SENSOR_PORTRAIT = 5;
	final int SCREEN_SENSOR = 6;

	/////////////////////////
	/// DIRECTORIES
	/////////////////////////

	class AssetDir {

		public String[] files;
		public int current;
		public String path;
	}

	private int last_dir_id = 1;

	private final SparseArray<AssetDir> dirs;

	public int dir_open(String path) {

		AssetDir ad = new AssetDir();
		ad.current = 0;
		ad.path = path;

		try {
			ad.files = am.list(path);
			// no way to find path is directory or file exactly.
			// but if ad.files.length==0, then it's an empty directory or file.
			if (ad.files.length == 0) {
				return -1;
			}
		} catch (IOException e) {

			System.out.printf("Exception on dir_open: %s\n", e);
			return -1;
		}

		++last_dir_id;
		dirs.put(last_dir_id, ad);

		return last_dir_id;
	}

	public boolean dir_is_dir(int id) {
		if (dirs.get(id) == null) {
			System.out.printf("dir_next: invalid dir id: %d\n", id);
			return false;
		}
		AssetDir ad = dirs.get(id);
		//System.out.printf("go next: %d,%d\n",ad.current,ad.files.length);
		int idx = ad.current;
		if (idx > 0)
			idx--;

		if (idx >= ad.files.length)
			return false;
		String fname = ad.files[idx];

		try {
			if (ad.path.equals(""))
				am.open(fname);
			else
				am.open(ad.path + "/" + fname);
			return false;
		} catch (Exception e) {
			return true;
		}
	}

	public String dir_next(int id) {

		if (dirs.get(id) == null) {
			System.out.printf("dir_next: invalid dir id: %d\n", id);
			return "";
		}

		AssetDir ad = dirs.get(id);
		//System.out.printf("go next: %d,%d\n",ad.current,ad.files.length);

		if (ad.current >= ad.files.length) {
			ad.current++;
			return "";
		}
		String r = ad.files[ad.current];
		ad.current++;
		return r;
	}

	public void dir_close(int id) {

		if (dirs.get(id) == null) {
			System.out.printf("dir_close: invalid dir id: %d\n", id);
			return;
		}

		dirs.remove(id);
	}

	GodotIO(Activity p_activity) {

		am = p_activity.getAssets();
		activity = p_activity;

		dirs = new SparseArray<>();
		String androidId = Settings.Secure.getString(activity.getContentResolver(),
				Settings.Secure.ANDROID_ID);
		if (androidId == null) {
			androidId = "";
		}

		uniqueId = androidId;
	}

	/////////////////////////
	// AUDIO
	/////////////////////////

	private Object buf;
	private Thread mAudioThread;
	private AudioTrack mAudioTrack;

	public Object audioInit(int sampleRate, int desiredFrames) {
		int channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
		int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
		int frameSize = 4;

		System.out.printf("audioInit: initializing audio:\n");

		//Log.v("Godot", "Godot audio: wanted " + (isStereo ? "stereo" : "mono") + " " + (is16Bit ? "16-bit" : "8-bit") + " " + ((float)sampleRate / 1000f) + "kHz, " + desiredFrames + " frames buffer");

		// Let the user pick a larger buffer if they really want -- but ye
		// gods they probably shouldn't, the minimums are horrifyingly high
		// latency already
		desiredFrames = Math.max(desiredFrames, (AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) + frameSize - 1) / frameSize);

		mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
				channelConfig, audioFormat, desiredFrames * frameSize, AudioTrack.MODE_STREAM);

		audioStartThread();

		//Log.v("Godot", "Godot audio: got " + ((mAudioTrack.getChannelCount() >= 2) ? "stereo" : "mono") + " " + ((mAudioTrack.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT) ? "16-bit" : "8-bit") + " " + ((float)mAudioTrack.getSampleRate() / 1000f) + "kHz, " + desiredFrames + " frames buffer");

		buf = new short[desiredFrames * 2];
		return buf;
	}

	public void audioStartThread() {
		mAudioThread = new Thread(new Runnable() {
			public void run() {
				mAudioTrack.play();
				GodotLib.audio();
			}
		});

		// I'd take REALTIME if I could get it!
		mAudioThread.setPriority(Thread.MAX_PRIORITY);
		mAudioThread.start();
	}

	public void audioWriteShortBuffer(short[] buffer) {
		for (int i = 0; i < buffer.length;) {
			int result = mAudioTrack.write(buffer, i, buffer.length - i);
			if (result > 0) {
				i += result;
			} else if (result == 0) {
				try {
					Thread.sleep(1);
				} catch (InterruptedException e) {
					// Nom nom
				}
			} else {
				Log.w("Godot", "Godot audio: error return from write(short)");
				return;
			}
		}
	}

	public void audioQuit() {
		if (mAudioThread != null) {
			try {
				mAudioThread.join();
			} catch (Exception e) {
				Log.v("Godot", "Problem stopping audio thread: " + e);
			}
			mAudioThread = null;

			//Log.v("Godot", "Finished waiting for audio thread");
		}

		if (mAudioTrack != null) {
			mAudioTrack.stop();
			mAudioTrack = null;
		}
	}

	public void audioPause(boolean p_pause) {

		if (p_pause)
			mAudioTrack.pause();
		else
			mAudioTrack.play();
	}

	/////////////////////////
	// MISCELLANEOUS OS IO
	/////////////////////////

	public int openURI(String p_uri) {

		try {
			String path = p_uri;
			String type = "";
			if (path.startsWith("/")) {
				//absolute path to filesystem, prepend file://
				path = "file://" + path;
				if (p_uri.endsWith(".png") || p_uri.endsWith(".jpg") || p_uri.endsWith(".gif") || p_uri.endsWith(".webp")) {

					type = "image/*";
				}
			}

			Intent intent = new Intent();
			intent.setAction(Intent.ACTION_VIEW);
			if (!type.equals("")) {
				intent.setDataAndType(Uri.parse(path), type);
			} else {
				intent.setData(Uri.parse(path));
			}

			activity.startActivity(intent);
			return 0;
		} catch (ActivityNotFoundException e) {

			return 1;
		}
	}

	public String getCacheDir() {
		return activity.getCacheDir().getAbsolutePath();
	}

	public String getDataDir() {
		return activity.getFilesDir().getAbsolutePath();
	}

	public String getLocale() {

		return Locale.getDefault().toString();
	}

	public String getModel() {
		return Build.MODEL;
	}

	public int getScreenDPI() {
		DisplayMetrics metrics = activity.getApplicationContext().getResources().getDisplayMetrics();
		return (int)(metrics.density * 160f);
	}

	public int[] getWindowSafeArea() {
		DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
		Display display = activity.getWindowManager().getDefaultDisplay();
		Point size = new Point();
		display.getRealSize(size);

		int result[] = { 0, 0, size.x, size.y };
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
			DisplayCutout cutout = insets.getDisplayCutout();
			if (cutout != null) {
				int insetLeft = cutout.getSafeInsetLeft();
				int insetTop = cutout.getSafeInsetTop();
				result[0] = insetLeft;
				result[1] = insetTop;
				result[2] -= insetLeft + cutout.getSafeInsetRight();
				result[3] -= insetTop + cutout.getSafeInsetBottom();
			}
		}
		return result;
	}

	public void showKeyboard(String p_existing_text, boolean p_multiline, int p_max_input_length, int p_cursor_start, int p_cursor_end) {
		if (edit != null)
			edit.showKeyboard(p_existing_text, p_multiline, p_max_input_length, p_cursor_start, p_cursor_end);

		//InputMethodManager inputMgr = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
		//inputMgr.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
	};

	public void hideKeyboard() {
		if (edit != null)
			edit.hideKeyboard();
	};

	public void setScreenOrientation(int p_orientation) {

		switch (p_orientation) {

			case SCREEN_LANDSCAPE: {
				activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
			} break;
			case SCREEN_PORTRAIT: {
				activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
			} break;
			case SCREEN_REVERSE_LANDSCAPE: {
				activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
			} break;
			case SCREEN_REVERSE_PORTRAIT: {
				activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
			} break;
			case SCREEN_SENSOR_LANDSCAPE: {
				activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
			} break;
			case SCREEN_SENSOR_PORTRAIT: {
				activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
			} break;
			case SCREEN_SENSOR: {
				activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
			} break;
		}
	};

	public int getScreenOrientation() {
		return activity.getRequestedOrientation();
	}

	public void setEdit(GodotEditText _edit) {
		edit = _edit;
	}

	public void playVideo(String p_path) {
		Uri filePath = Uri.parse(p_path);
		mediaPlayer = new MediaPlayer();

		try {
			mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
			mediaPlayer.setDataSource(activity.getApplicationContext(), filePath);
			mediaPlayer.prepare();
			mediaPlayer.start();
		} catch (IOException e) {
			System.out.println("IOError while playing video");
		}
	}

	public boolean isVideoPlaying() {
		if (mediaPlayer != null) {
			return mediaPlayer.isPlaying();
		}
		return false;
	}

	public void pauseVideo() {
		if (mediaPlayer != null) {
			mediaPlayer.pause();
		}
	}

	public void stopVideo() {
		if (mediaPlayer != null) {
			mediaPlayer.release();
			mediaPlayer = null;
		}
	}

	public static final int SYSTEM_DIR_DESKTOP = 0;
	public static final int SYSTEM_DIR_DCIM = 1;
	public static final int SYSTEM_DIR_DOCUMENTS = 2;
	public static final int SYSTEM_DIR_DOWNLOADS = 3;
	public static final int SYSTEM_DIR_MOVIES = 4;
	public static final int SYSTEM_DIR_MUSIC = 5;
	public static final int SYSTEM_DIR_PICTURES = 6;
	public static final int SYSTEM_DIR_RINGTONES = 7;

	public String getSystemDir(int idx, boolean shared_storage) {
		String what;
		switch (idx) {
			case SYSTEM_DIR_DESKTOP:
			default: {
				what = null; // This leads to the app specific external root directory.
			} break;

			case SYSTEM_DIR_DCIM: {
				what = Environment.DIRECTORY_DCIM;
			} break;

			case SYSTEM_DIR_DOCUMENTS: {
				what = Environment.DIRECTORY_DOCUMENTS;
			} break;

			case SYSTEM_DIR_DOWNLOADS: {
				what = Environment.DIRECTORY_DOWNLOADS;
			} break;

			case SYSTEM_DIR_MOVIES: {
				what = Environment.DIRECTORY_MOVIES;
			} break;

			case SYSTEM_DIR_MUSIC: {
				what = Environment.DIRECTORY_MUSIC;
			} break;

			case SYSTEM_DIR_PICTURES: {
				what = Environment.DIRECTORY_PICTURES;
			} break;

			case SYSTEM_DIR_RINGTONES: {
				what = Environment.DIRECTORY_RINGTONES;
			} break;
		}

		if (shared_storage) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				Log.w(TAG, "Shared storage access is limited on Android 10 and higher.");
			}
			if (TextUtils.isEmpty(what)) {
				return Environment.getExternalStorageDirectory().getAbsolutePath();
			} else {
				return Environment.getExternalStoragePublicDirectory(what).getAbsolutePath();
			}
		} else {
			return activity.getExternalFilesDir(what).getAbsolutePath();
		}
	}

	public String getUniqueID() {
		return uniqueId;
	}
}
