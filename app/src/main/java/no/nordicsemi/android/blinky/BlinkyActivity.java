/*
 * Copyright (c) 2018, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.android.blinky;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import no.nordicsemi.android.ble.data.Data;
import no.nordicsemi.android.ble.livedata.state.ConnectionState;
import no.nordicsemi.android.blinky.adapter.DiscoveredBluetoothDevice;
import no.nordicsemi.android.blinky.profile.callback.BlinkyButtonDataCallback;
import no.nordicsemi.android.blinky.utils.HexUtil;
import no.nordicsemi.android.blinky.viewmodels.BlinkyViewModel;
import no.nordicsemi.android.log.LogContract;

@SuppressWarnings("ConstantConditions")
public class BlinkyActivity extends AppCompatActivity {
	public static final String EXTRA_DEVICE = "no.nordicsemi.android.blinky.EXTRA_DEVICE";

	private BlinkyViewModel viewModel;
	private static int notification_flag = 0;
	private static int SERVER_RUNNING_OK = 1;
	private static int SERVER_RUNNING_ERROR1 = 2;
	private static int SERVER_RUNNING_ERROR2 = 3;
	private ServerSocketThread mServerThread = null;
	private TextView txt = null;

	@BindView(R.id.led_switch) SwitchMaterial led;
	//@BindView(R.id.button_state) TextView buttonState;

	private  Handler mHandler = new Handler(){
		@Override
		public void handleMessage(Message msg) {
			if (msg.what == SERVER_RUNNING_OK)
			{
				addText(txt, "server started OK");
			} else if (msg.what == SERVER_RUNNING_ERROR1)
			{
				addText(txt, "server send error");
			} else if (msg.what == SERVER_RUNNING_ERROR2)
			{
				addText(txt, "server open error");
			}
		}
	};
	public static String int2ip(int ipInt) {
		StringBuilder sb = new StringBuilder();
		sb.append(ipInt & 0xFF).append(".");
		sb.append((ipInt >> 8) & 0xFF).append(".");
		sb.append((ipInt >> 16) & 0xFF).append(".");
		sb.append((ipInt >> 24) & 0xFF);
		return sb.toString();
	}


	public  String getLocalIpAddress() {
		try {
			WifiManager wifiManager = (WifiManager)(getApplicationContext().getSystemService(Context.WIFI_SERVICE));
			WifiInfo wifiInfo = wifiManager.getConnectionInfo();
			int i = wifiInfo.getIpAddress();
			return int2ip(i);
		} catch (Exception ex) {
			return " get wifi network error\n" + ex.getMessage();
		}
		// return null;
	}
	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_blinky);
		ButterKnife.bind(this);

		final Intent intent = getIntent();
		final DiscoveredBluetoothDevice device = intent.getParcelableExtra(EXTRA_DEVICE);
		final String deviceName = device.getName();
		final String deviceAddress = device.getAddress();

		final MaterialToolbar toolbar = findViewById(R.id.toolbar);
		toolbar.setTitle(deviceName != null ? deviceName : getString(R.string.unknown_device));
		toolbar.setSubtitle(deviceAddress);
		setSupportActionBar(toolbar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		// Configure the view model.
		viewModel = new ViewModelProvider(this).get(BlinkyViewModel.class);
		viewModel.connect(device);

		// Set up views.
		final TextView ledState = findViewById(R.id.led_state);
		final LinearLayout progressContainer = findViewById(R.id.progress_container);
		final TextView connectionState = findViewById(R.id.connection_state);
		final View content = findViewById(R.id.device_container);
		final View notSupported = findViewById(R.id.not_supported);
		final EditText et = (EditText) findViewById(R.id.et);
		et.setText("1415ff803f0f88952008");
		txt = (TextView) findViewById(R.id.txt);
		txt.setMovementMethod(ScrollingMovementMethod.getInstance());

		Button btn_clearlog = (Button) findViewById(R.id.btn_clearlog);
		btn_clearlog.setText("clearlog");
		btn_clearlog.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				txt.setText("");
				int offset = txt.getLineCount() * txt.getLineHeight();
				if (offset > txt.getHeight()) {
					txt.scrollTo(0, offset - txt.getHeight());
				}
			}
		});

		Button btn_notification = (Button) findViewById(R.id.btn_notification);
		btn_notification.setText(this.getString(R.string.notification));
		btn_notification.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				if (notification_flag == 0) {
					notification_flag = 1;
					viewModel.setNotificationCallback(buttonCallback);
					viewModel.setNotificationState(true);
					addText(txt, "Start notification");
					addText(txt, getLocalIpAddress());

					if (mServerThread != null) {
						mServerThread.shutdown();
						mServerThread = null;
					}
					mServerThread = new ServerSocketThread();
					mServerThread.start();

				} else if (notification_flag == 1){
					notification_flag = 0;

					viewModel.setNotificationState(false);
					if (mServerThread != null) {
						mServerThread.shutdown();
						mServerThread = null;
					}
					addText(txt, "Stop notification");
				}
			}
		});
		Button btn = (Button) findViewById(R.id.btn);
		btn.setText(this.getString(R.string.write));
		btn.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				String hex = et.getText().toString();
				if (TextUtils.isEmpty(hex)) {
					return;
				}
				viewModel.writeDownTouchConfig(HexUtil.hexStringToBytes(hex));
				addText(txt, "Write touch config done");
			}
		});
		led.setOnCheckedChangeListener((buttonView, isChecked) -> viewModel.setLedState(isChecked));
		viewModel.getConnectionState().observe(this, state -> {
			switch (state.getState()) {
				case CONNECTING:
					progressContainer.setVisibility(View.VISIBLE);
					notSupported.setVisibility(View.GONE);
					connectionState.setText(R.string.state_connecting);
					break;
				case INITIALIZING:
					connectionState.setText(R.string.state_initializing);
					break;
				case READY:
					progressContainer.setVisibility(View.GONE);
					content.setVisibility(View.VISIBLE);
					onConnectionStateChanged(true);
					break;
				case DISCONNECTED:
					if (state instanceof ConnectionState.Disconnected) {
						final ConnectionState.Disconnected stateWithReason = (ConnectionState.Disconnected) state;
						if (stateWithReason.isNotSupported()) {
							progressContainer.setVisibility(View.GONE);
							notSupported.setVisibility(View.VISIBLE);
						}
					}
					// fallthrough
				case DISCONNECTING:
					onConnectionStateChanged(false);
					break;
			}
		});
		viewModel.getLedState().observe(this, isOn -> {
			ledState.setText(isOn ? R.string.turn_on : R.string.turn_off);
			led.setChecked(isOn);

		});
		/*viewModel.getButtonState().observe(this,
				pressed -> buttonState.setText(pressed ?
						R.string.button_pressed : R.string.button_released));*/
	}

	@OnClick(R.id.action_clear_cache)
	public void onTryAgainClicked() {
		viewModel.reconnect();
	}

	private void onConnectionStateChanged(final boolean connected) {
		led.setEnabled(connected);
		if (!connected) {
			led.setChecked(false);
	//		buttonState.setText(R.string.button_unknown);
		}
	}

	private void addText(TextView textView, String content) {
		textView.append(content);
		textView.append("\n");
		int offset = textView.getLineCount() * textView.getLineHeight();
		if (offset > textView.getHeight()) {
			textView.scrollTo(0, offset - textView.getHeight());
		}
	}
	/**
	 * The Button callback will be notified when a notification from Button characteristic
	 * has been received, or its data was read.
	 * <p>
	 * If the data received are valid (single byte equal to 0x00 or 0x01), the
	 * {@link BlinkyButtonDataCallback#onButtonStateChanged} will be called.
	 * Otherwise, the {@link BlinkyButtonDataCallback#onInvalidDataReceived(BluetoothDevice, Data)}
	 * will be called with the data received.
	 */
	private	final BlinkyButtonDataCallback buttonCallback = new BlinkyButtonDataCallback() {
		@Override
		public void onButtonStateChanged(@NonNull final BluetoothDevice device,
										 final boolean pressed) {

		}

		@Override
		public void onInvalidDataReceived(@NonNull final BluetoothDevice device,
										  @NonNull final Data data) {
			if (mServerThread != null)
				mServerThread.serverSendMessage(data.getValue());
		}
	};

	public class ServerSocketThread extends Thread{
		private PrintStream writer;
		private Socket msocket = null;
		private ServerSocket mserverSocket = null;
		private OutputStream msocketWriter = null;
		private boolean bOnRunning = false;
		@Override
		public void run() {
			try {
				// 创建ServerSocket
				if (mserverSocket == null) {
					mserverSocket = new ServerSocket(9999);
					mserverSocket.setReuseAddress(true);
					System.out.println("--开启服务器，监听端口 9999--");
					// send handler msg
					Message msg = Message.obtain();
					msg.obj = null;
					msg.what = SERVER_RUNNING_OK;
					mHandler.sendMessage(msg);
					bOnRunning = true;
					msocket = mserverSocket.accept(); //等待客户端连接
					System.out.println("得到客户端连接：" + msocket);
					msocketWriter = msocket.getOutputStream();
				}
			} catch (IOException e) {
				e.printStackTrace();
				try {
					if (mserverSocket != null) {
						mserverSocket.close();
					}
				} catch (IOException ERROR){
					ERROR.printStackTrace();
				}
				mserverSocket = null;

				Message msg = Message.obtain();
				msg.obj = null;
				msg.what = SERVER_RUNNING_ERROR2;
				mHandler.sendMessage(msg);
			}
		}


		public  void shutdown() {
			if(mserverSocket != null)
			{
				if (msocket != null) {
					try {
						msocketWriter.close();
						msocket.close();
						msocketWriter = null;
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				try {
					mserverSocket.close();
					mserverSocket = null;
				} catch (IOException e) {
					e.printStackTrace();
				}
				bOnRunning = false;
			}
		}

		public  boolean getServerState()
		{
			return bOnRunning;
		}

		//通过socket来给客户端发送消息
		public void serverSendMessage(final byte[] mServerSendMessage) {
			new Thread() {
				@Override
				public void run() {
					try {
						if (msocketWriter != null) {
							msocketWriter.write(mServerSendMessage);
						}
					} catch (IOException e) {
						e.printStackTrace();
						try {
							msocketWriter.close();
							msocketWriter = null;
						} catch (IOException e0) {
							e0.printStackTrace();
						}
						Message msg = Message.obtain();
						msg.obj = null;
						msg.what = SERVER_RUNNING_ERROR1;
						mHandler.sendMessage(msg);
					}
				}
			}.start();
		}
	}
}
