/*
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.thoughtcrime.redphone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import org.thoughtcrime.redphone.audio.OutgoingRinger;
import org.thoughtcrime.redphone.codec.CodecSetupException;
import org.thoughtcrime.redphone.contacts.PersonInfo;
import org.thoughtcrime.redphone.directory.DirectoryUpdateReceiver;
import org.thoughtcrime.redphone.ui.ApplicationPreferencesActivity;
import org.thoughtcrime.redphone.ui.CallControls;
import org.thoughtcrime.redphone.ui.CallScreen;
import org.thoughtcrime.redphone.ui.QualityReporting;

import java.util.ArrayList;

/**
 * The main UI class for RedPhone.  Most of the heavy lifting is
 * done by RedPhoneService, so this activity is mostly responsible
 * for receiving events about the state of ongoing calls and displaying
 * the appropriate UI components.
 *
 * @author Moxie Marlinspike
 *
 */
public class RedPhone extends Activity {

  private static final int REMOTE_TERMINATE = 0;
  private static final int LOCAL_TERMINATE  = 1;

  public static final int STATE_IDLE      = 0;
  public static final int STATE_RINGING   = 2;
  public static final int STATE_DIALING   = 3;
  public static final int STATE_ANSWERING = 4;
  public static final int STATE_CONNECTED = 5;

  public static final int HANDLE_CALL_CONNECTED          = 0;
  public static final int HANDLE_WAITING_FOR_RESPONDER   = 1;
  public static final int HANDLE_SERVER_FAILURE          = 2;
  public static final int HANDLE_PERFORMING_HANDSHAKE    = 3;
  public static final int HANDLE_HANDSHAKE_FAILED        = 4;
  public static final int HANDLE_CONNECTING_TO_INITIATOR = 5;
  public static final int HANDLE_CALL_DISCONNECTED       = 6;
  public static final int HANDLE_CALL_RINGING            = 7;
  public static final int HANDLE_CODEC_INIT_FAILED       = 8;
  public static final int HANDLE_SERVER_MESSAGE          = 9;
  public static final int HANDLE_RECIPIENT_UNAVAILABLE   = 10;
  public static final int HANDLE_INCOMING_CALL           = 11;
  public static final int HANDLE_OUTGOING_CALL           = 12;
  public static final int HANDLE_CALL_BUSY               = 13;
  public static final int HANDLE_LOGIN_FAILED            = 14;
  public static final int HANDLE_CLIENT_FAILURE          = 15;
  public static final int HANDLE_DEBUG_INFO              = 16;
  public static final int HANDLE_NO_SUCH_USER            = 17;
  public static final int HANDLE_CALL_CONNECTING         = 18;

  private final HandlerThread backgroundTaskThread = new HandlerThread("BackgroundUITasks");
  private final Handler callStateHandler           = new CallStateHandler();

  private int state;
  private boolean deliveringTimingData = false;
  private RedPhoneService redPhoneService;
  private CallScreen callScreen;
  private OutgoingRinger outgoingRinger;


  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    startServiceIfNecessary();
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    setContentView(R.layout.main);

    setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

    initializeResources();
  }


  @Override
  public void onResume() {
    super.onResume();

    initializeServiceBinding();
  }


  @Override
  public void onPause() {
    super.onPause();

    unbindService(serviceConnection);
  }

  @Override
  public void onConfigurationChanged(Configuration newConfiguration) {
    super.onConfigurationChanged(newConfiguration);
  }

  private void startServiceIfNecessary() {
    Intent intent = this.getIntent();
    String action = null;

    if (intent != null)
      action = intent.getAction();

    if (action != null &&
        (action.equals(Intent.ACTION_CALL) || action.equals(Intent.ACTION_DIAL) ||
         action.equals("android.intent.action.CALL_PRIVILEGED")))
    {
      Log.w("RedPhone", "Calling startService from within RedPhone!");
      String number = Uri.decode(intent.getData().getEncodedSchemeSpecificPart());
      Intent serviceIntent = new Intent();
      serviceIntent.setClass(this, RedPhoneService.class);
      serviceIntent.putExtra(Constants.REMOTE_NUMBER, number);
      startService(serviceIntent);
    }
  }

  private void initializeServiceBinding() {
    Log.w("RedPHone", "Binding to RedPhoneService...");
    Intent bindIntent = new Intent(this, RedPhoneService.class);
    bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE);
  }

  private void initializeResources() {
    callScreen = (CallScreen)findViewById(R.id.callScreen);
    state      = STATE_IDLE;

    callScreen.setHangupButtonListener(new HangupButtonListener());
    callScreen.setIncomingCallActionListener(new IncomingCallActionListener());

    outgoingRinger = new OutgoingRinger(this);

    DirectoryUpdateReceiver.scheduleDirectoryUpdate(this);
  }

  private void sendInstallLink(String user) {
    String message = "I'd like to call you securely using RedPhone." +
                     " You can install RedPhone from Android Market: " +
                     "http://market.android.com/search?q=pname:org.thoughtcrime.redphone";

    ArrayList<String> messages = SmsManager.getDefault().divideMessage(message);
    SmsManager.getDefault().sendMultipartTextMessage(user, null, messages, null, null);
  }

  private void handleAnswerCall() {
    state = STATE_ANSWERING;
    callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Answering...");
    Intent intent = new Intent(this, RedPhoneService.class);
    intent.setAction(RedPhoneService.ACTION_ANSWER_CALL);
    startService(intent);
  }

  private void handleDenyCall() {
    state = STATE_IDLE;

    Intent intent = new Intent(this, RedPhoneService.class);
    intent.setAction(RedPhoneService.ACTION_DENY_CALL);
    startService(intent);

    callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Ending call");
    delayedFinish();
  }

  private void handleIncomingCall(String remoteNumber) {
    state = STATE_RINGING;
    callScreen.setIncomingCall(PersonInfo.getInstance(this, remoteNumber));
  }

  private void handleOutgoingCall(String remoteNumber) {
    state = STATE_DIALING;
    callScreen.setActiveCall(PersonInfo.getInstance(this, remoteNumber), "Dialing...");
  }

  private void handleTerminate( int terminationType ) {
    Log.w("RedPhone", "handleTerminate called");
    Log.w("RedPhone", "Termination Stack:", new Exception() );

    if( state == STATE_DIALING ) {
      if (terminationType == LOCAL_TERMINATE) {
        callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Cancelling call");
      } else {
        callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Call rejected");
      }
    } else if (state != STATE_IDLE) {
      callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Ending call");
    }

    state = STATE_IDLE;
    delayedFinish();
  }

  private void handleCallRinging() {
    outgoingRinger.playRing();
    callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Ringing...");
  }

  private void handleCallBusy() {
    outgoingRinger.playBusy();
    callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Busy...");

    state = STATE_IDLE;
    delayedFinish();
  }

  private void handleCallConnected(String sas) {
    outgoingRinger.playComplete();
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);
    callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Connected", sas);
    state = STATE_CONNECTED;
    redPhoneService.notifyCallConnectionUIUpdateComplete();
  }

  private void handleDebugInfo( String info ) {
//    debugCard.setInfo( info );
  }

  private void handleConnectingToInitiator() {
    callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Connecting...");
  }

  private void handleHandshakeFailed() {
    state = STATE_IDLE;
    outgoingRinger.playFailure();
    callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Handshake failed!");
    delayedFinish();
  }

  private void handleRecipientUnavailable() {
    state = STATE_IDLE;
    outgoingRinger.playFailure();
    callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Recipient unavailable");
    delayedFinish();
  }

  private void handlePerformingHandshake() {
    outgoingRinger.playHandshake();
    callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Performing handshake...");
  }

  private void handleServerFailure() {
    state = STATE_IDLE;
    outgoingRinger.playFailure();
    callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Server failed!");
    delayedFinish();
  }

  private void handleClientFailure(String msg) {
    state = STATE_IDLE;
    outgoingRinger.playFailure();
    callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Client failed");
    if( msg != null && !isFinishing() ) {
      AlertDialog.Builder ad = new AlertDialog.Builder(this);
      ad.setTitle("Fatal Error");
      ad.setMessage(msg);
      ad.setCancelable(false);
      ad.setPositiveButton("Ok", new OnClickListener() {
        public void onClick(DialogInterface dialog, int arg) {
          RedPhone.this.handleTerminate(LOCAL_TERMINATE);
        }
      });
      ad.show();
    }
  }

  private void handleLoginFailed() {
    state = STATE_IDLE;
    outgoingRinger.playFailure();
    callScreen.setActiveCall(redPhoneService.getRemotePersonInfo(), "Login failed!");
    delayedFinish();
  }

  private void handleServerMessage(String message) {
    if( isFinishing() ) return; //we're already shutting down, this might crash
    AlertDialog.Builder ad = new AlertDialog.Builder(this);
    ad.setTitle("Message from the server:");
    ad.setMessage(message);
    ad.setCancelable(false);
    ad.setPositiveButton("Ok", new OnClickListener() {
      public void onClick(DialogInterface dialog, int arg) {
        RedPhone.this.handleTerminate(LOCAL_TERMINATE);
      }
    });
    ad.show();
  }

  private void handleNoSuchUser(final String user) {
    if (isFinishing()) return; // XXX Stuart added this check above, not sure why, so I'm repeating in ignorance. - moxie
    outgoingRinger.playFailure();
    AlertDialog.Builder dialog = new AlertDialog.Builder(this);
    dialog.setTitle("Number not registered with RedPhone!");
    dialog.setIcon(android.R.drawable.ic_dialog_info);
    dialog.setMessage("The number you dialed is not registered with RedPhone.  " +
                      "Both parties of a call need to have RedPhone installed in " +
                      "order to have a secure conversation.  Would you like to send " +
                      "a RedPhone install link to the contact you were trying to dial?");
    dialog.setCancelable(false);
    dialog.setPositiveButton("Yes!", new OnClickListener() {
      public void onClick(DialogInterface dialog, int arg) {
        RedPhone.this.sendInstallLink(user);
        RedPhone.this.handleTerminate(LOCAL_TERMINATE);
      }
    });
    dialog.setNegativeButton("No thanks!", new OnClickListener() {
      public void onClick(DialogInterface dialog, int arg) {
        RedPhone.this.handleTerminate(LOCAL_TERMINATE);
      }
    });
    dialog.show();
  }

  private void handleCallConnecting() {
    outgoingRinger.playSonar();
  }

  private void handleCodecFailure(CodecSetupException e) {
    Log.w("RedPhone", e);
    Toast.makeText(this, "Codec Failed to Initialize", Toast.LENGTH_LONG).show();
    handleTerminate( LOCAL_TERMINATE );
  }

  private void delayedFinish() {
    callStateHandler.postDelayed(new Runnable() {

    public void run() {
      outgoingRinger.stop();
      Log.w("RedPhone", "Releasing wake locks...");
      if (Release.DELIVER_DIAGNOSTIC_DATA &&
          ApplicationPreferencesActivity.getAskUserToSendDiagnosticData(RedPhone.this)) {
        if( !deliveringTimingData) {
          deliveringTimingData = true;
          QualityReporting.sendDiagnosticData(RedPhone.this);
        }
      } else {
        RedPhone.this.finish();
      }
    }}, 3000);
  }

  private class CallStateHandler extends Handler {
    @Override
    public void handleMessage(Message message) {
      Log.w("RedPhone", "Got message from service: " + message.what);
      switch (message.what) {
      case HANDLE_CALL_CONNECTED:          handleCallConnected((String)message.obj);                break;
      case HANDLE_SERVER_FAILURE:          handleServerFailure();                                   break;
      case HANDLE_PERFORMING_HANDSHAKE:    handlePerformingHandshake();                             break;
      case HANDLE_HANDSHAKE_FAILED:        handleHandshakeFailed();                                 break;
      case HANDLE_CONNECTING_TO_INITIATOR: handleConnectingToInitiator();                           break;
      case HANDLE_CALL_RINGING:            handleCallRinging();                                     break;
      case HANDLE_CALL_DISCONNECTED:       handleTerminate( REMOTE_TERMINATE );                     break;
      case HANDLE_SERVER_MESSAGE:          handleServerMessage((String)message.obj);                break;
      case HANDLE_NO_SUCH_USER:            handleNoSuchUser((String)message.obj);                   break;
      case HANDLE_RECIPIENT_UNAVAILABLE:   handleRecipientUnavailable();                            break;
      case HANDLE_CODEC_INIT_FAILED:		   handleCodecFailure( (CodecSetupException) message.obj ); break;
      case HANDLE_INCOMING_CALL:           handleIncomingCall((String)message.obj);                 break;
      case HANDLE_OUTGOING_CALL:           handleOutgoingCall((String)message.obj);                 break;
      case HANDLE_CALL_BUSY:               handleCallBusy();                                        break;
      case HANDLE_LOGIN_FAILED:            handleLoginFailed();                                     break;
      case HANDLE_CLIENT_FAILURE:			     handleClientFailure((String)message.obj);                break;
      case HANDLE_DEBUG_INFO:				       handleDebugInfo((String)message.obj);					          break;
      case HANDLE_CALL_CONNECTING:         handleCallConnecting();                                  break;
      }
    }
  }

  private class HangupButtonListener implements CallControls.HangupButtonListener {
    public void onClick() {
      Log.w("RedPhone", "Hangup pressed, handling termination now...");
      Intent intent = new Intent(RedPhone.this, RedPhoneService.class);
      intent.setAction(RedPhoneService.ACTION_HANGUP_CALL);
      startService(intent);

      RedPhone.this.handleTerminate( LOCAL_TERMINATE );
    }
  }

  private class IncomingCallActionListener implements CallControls.IncomingCallActionListener {
    @Override
    public void onAcceptClick() {
      RedPhone.this.handleAnswerCall();
    }
    @Override
    public void onDenyClick() {
      RedPhone.this.handleDenyCall();
    }
  }

  private ServiceConnection serviceConnection = new ServiceConnection() {
    public void onServiceConnected(ComponentName className, IBinder service) {
      RedPhone.this.redPhoneService  = ((RedPhoneService.RedPhoneServiceBinder)service).getService();
      redPhoneService.setCallStateHandler(callStateHandler);

      PersonInfo personInfo = redPhoneService.getRemotePersonInfo();

      switch (redPhoneService.getState()) {
      case STATE_IDLE:      callScreen.reset();                         break;
      case STATE_RINGING:   handleIncomingCall(personInfo.getNumber()); break;
      case STATE_DIALING:   handleOutgoingCall(personInfo.getNumber()); break;
      case STATE_ANSWERING: handleAnswerCall();                         break;
      case STATE_CONNECTED: handleCallConnected("XXXX");                break;
      }
    }

    public void onServiceDisconnected(ComponentName name) {
      redPhoneService.setCallStateHandler(null);
    }
  };

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event)
  {

    boolean result = super.onKeyDown(keyCode, event);

    //limit the maximum volume to 0.9 [echo prevention]
    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
      AudioManager audioManager = (AudioManager)
      ApplicationContext.getInstance().getContext().getSystemService(Context.AUDIO_SERVICE);
      int curVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
      int maxVol = (int) (audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) * 0.9);
      Log.d("RedPhone", "volume up key press detected: " + curVol + " / " + maxVol );
      if(  curVol > maxVol ) {
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,maxVol,0);
      }
    }
     return result;
  }
}