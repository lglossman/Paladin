package com.nanospark.gard.model.door;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.nanospark.gard.GarD;
import com.nanospark.gard.R;
import com.nanospark.gard.events.BoardConnected;
import com.nanospark.gard.events.BoardDisconnected;
import com.nanospark.gard.events.CommandFailed;
import com.nanospark.gard.events.CommandProcessed;
import com.nanospark.gard.events.CommandSent;
import com.nanospark.gard.events.DoorStateChanged;
import com.nanospark.gard.events.VoiceRecognitionDisabled;
import com.nanospark.gard.events.VoiceRecognitionEnabled;
import com.nanospark.gard.model.CommandSource;
import com.nanospark.gard.model.scheduler.Schedule;
import com.nanospark.gard.model.scheduler.ScheduleManager;
import com.nanospark.gard.model.user.User;
import com.nanospark.gard.sms.SmsManager;
import com.nanospark.gard.voice.VoiceRecognizer;
import com.squareup.otto.Subscribe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ioio.lib.api.DigitalInput;
import ioio.lib.api.DigitalOutput;
import ioio.lib.api.IOIO;
import ioio.lib.api.exception.ConnectionLostException;
import mobi.tattu.utils.Tattu;
import mobi.tattu.utils.ToastManager;
import mobi.tattu.utils.persistance.datastore.DataStore;
import roboguice.RoboGuice;
import roboguice.util.Ln;

/**
 * Created by Leandro on 9/8/2015
 */
public abstract class Door {

    public static final int TIME_WAIT_FOR_DOOR_STATE = 30000;
    public static final int TIME_PIN_ACTIVE_FOR_COMMAND = 2000;

    private int mId;
    private State mState = State.UNKNOWN;
    private int mControlPinNumber;
    private int mClosedPinNumber;
    private int mOpenPinNumber;
    private DigitalOutput mOutputPin;
    private DigitalInput mClosedPin;
    private DigitalInput mOpenPin;
    private boolean mActivatePin;
    private Config mConfig;
    private Handler mAutoCloseHandler;
    private Handler mActivationTimeoutHandler;
    private Command mPendingCommand;
    private boolean mBoardConnected = false;
    private boolean mVoiceEnabled;

    @Inject
    private DataStore mDataStore;
    @Inject
    private SmsManager mSmsManager;
    @Inject
    private ScheduleManager mScheduleManager;

    public Door(int id, Integer controlPinNumber, Integer closedPinNumber, Integer openPinNumber) {
        mId = id;
        mControlPinNumber = controlPinNumber;
        mClosedPinNumber = closedPinNumber;
        mOpenPinNumber = openPinNumber;
        mDataStore = DataStore.getInstance();
        mAutoCloseHandler = new Handler(Looper.getMainLooper());
        mActivationTimeoutHandler = new Handler(Looper.getMainLooper());
        restore();
        Tattu.register(this);
    }

    public static final Door getInstance(Integer id) {
        switch (id) {
            case 1:
                return RoboGuice.getInjector(GarD.instance).getInstance(One.class);
            case 2:
                return RoboGuice.getInjector(GarD.instance).getInstance(Two.class);
        }
        throw new IllegalArgumentException("No door with id " + id);
    }

    public static void setAutoClose(TimeUnit unit, long value) {
        AutoCloseConfig config = getAutoCloseConfig();
        config.unit = unit;
        config.value = value;
        persist(config);
    }

    public static TimeUnit getAutoCloseUnit() {
        return getAutoCloseConfig().unit;
    }

    public static long getAutoCloseValue() {
        return getAutoCloseConfig().value;
    }

    public static void enableAutoClose() {
        AutoCloseConfig config = getAutoCloseConfig();
        config.enabled = true;
        persist(config);
    }

    public static void disableAutoClose() {
        AutoCloseConfig config = getAutoCloseConfig();
        config.enabled = false;
        persist(config);
    }

    public static boolean isAutoCloseEnabled() {
        return getAutoCloseConfig().enabled;
    }

    private static AutoCloseConfig getAutoCloseConfig() {
        if (sAutoCloseConfig == null) {
            sAutoCloseConfig = DataStore.getInstance().get(AutoCloseConfig.class.getSimpleName(), AutoCloseConfig.class).get();
            if (sAutoCloseConfig == null) {
                sAutoCloseConfig = new AutoCloseConfig();
            }
        }
        return sAutoCloseConfig;
    }

    public static void persist(AutoCloseConfig config) {
        DataStore.getInstance().put(AutoCloseConfig.class.getSimpleName(), config);
    }

    public static AutoCloseConfig sAutoCloseConfig;

    public static class AutoCloseConfig {
        public TimeUnit unit = TimeUnit.MINUTES;
        public long value = 10;
        public boolean enabled = false;
    }

    public static final List<Door> getDoors() {
        return Arrays.asList(getInstance(1), getInstance(2));
    }

    public static final List<Door> getEnabledDoors() {
        List<Door> doors = new ArrayList<>(2);
        for (Door door : getDoors()) if (door.isEnabled()) doors.add(door);
        return doors;
    }

    public boolean send(Command command) {
        if (!isEnabled()) {
            Ln.i("Command not processed, Door is disabled.");
            return false;
        }
        if (mHasLatching) {
            Ln.i("Command not processed, Latching is active.");
            return false;
        }
        return command.apply(this);
    }

    private void confirm(State state) {
        Log.i(toString(), "Confirmed door: " + this + " - state: " + state);
        mState = state;
        CommandSource source = null;
        if (mPendingCommand != null) {
            source = mPendingCommand.source;
        }
        Tattu.post(new DoorStateChanged(this, mState, source));
        if (mState != State.UNKNOWN) {
            mActivationTimeoutHandler.removeCallbacksAndMessages(null);
            Tattu.post(new CommandProcessed(this, mState, mPendingCommand));
            mPendingCommand = null;
        }
        if (isOpen()) {
            startAutoClose();
        }
    }

    private void startAutoClose() {
        if (!isAutoCloseEnabled()) return;

        long startTime = System.currentTimeMillis();
        mAutoCloseHandler.removeCallbacksAndMessages(null);
        mAutoCloseHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isOpen()) {
                    send(new Close(CommandSource.SCHEDULED_ACTION, "Auto closing", false));
                } else {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - startTime < 20000) {
                        mAutoCloseHandler.removeCallbacksAndMessages(null);
                        mAutoCloseHandler.postDelayed(this, 20000);
                    } else {
                        send(new Close(null, "Auto closing", false));
                    }
                }
            }
        }, getAutoCloseUnit().toMillis(getAutoCloseValue()) + 10000); // as per wireframe: Wait {Auto-Close Interval} + 10s
    }

    @Subscribe
    public void on(CommandSent event) {
        if (event.door != this) return;

        mActivatePin = true;
        mPendingCommand = event.command;
        mActivationTimeoutHandler.removeCallbacksAndMessages(null);
        // For touch and voice issued commands since the user should be aware of any failure to
        // open/close the app should only process the command, not make attempts to close if the
        // door has not succeeded
        if (mPendingCommand.source != CommandSource.VOICE && mPendingCommand.source != CommandSource.TOUCH) {
            mActivationTimeoutHandler.postDelayed(new Runnable() {

                private int increment = 0;

                @Override
                public void run() {
                    increment++;
                    if (increment < 3) {
                        Log.d(Door.this.toString(), "Retrying command: " + event.command.toString());
                        mActivatePin = true;
                        mActivationTimeoutHandler.postDelayed(this, 20000);
                    } else {
                        Log.d(Door.this.toString(), "Retry failed: " + event.command.toString());
                        Tattu.post(new CommandFailed(event.door, event.command));
                        if (event.door.isEnabled()) {
                            mSmsManager.sendDoorAlert("PALADIN was unable to " + event.command.toString() + " your door.", null);
                        }
                    }
                }
            }, 20000);
        }
    }

    private void on(BoardDisconnected event) {
        mBoardConnected = false;
    }

    private void on(BoardConnected event) {
        mBoardConnected = true;
    }

    @Subscribe
    public void on(VoiceRecognizer.StateChanged event) {
        if (event.door.equals(this)) {
            mVoiceEnabled = event.state == VoiceRecognizer.State.STARTED;
        } else {
            mVoiceEnabled = false;
        }
    }

    public boolean isVoiceEnabled() {
        return mVoiceEnabled;
    }

    public State getState() {
        return !mBoardConnected ? State.UNKNOWN : mState;
    }

    public boolean isOpen() {
        return State.OPEN.equals(mState);
    }

    public boolean isClosed() {
        return State.CLOSED.equals(mState);
    }

    public boolean isReady() {
        return !State.UNKNOWN.equals(getState());
    }

    public int getId() {
        return mId;
    }

    public void disableVoiceRecognition() {
        Tattu.post(new VoiceRecognitionDisabled(this));
    }

    public void enableVoiceRecognition() {
        Tattu.post(new VoiceRecognitionEnabled(this));
    }

    public String getName() {
        return mConfig.name;
    }

    public void setName(String name) {
        mConfig.name = name;
        persist();
    }

    public String getOpenPhrase() {
        return mConfig.openPhrase;
    }

    public void setOpenPhrase(String openPhrase) {
        mConfig.openPhrase = openPhrase.toLowerCase();
        persist();
    }

    public String getClosePhrase() {
        return mConfig.closePhrase;
    }

    public void setClosePhrase(String closePhrase) {
        mConfig.closePhrase = closePhrase.toLowerCase();
        persist();
    }

    public boolean isEnabled() {
        return mConfig.enabled;
    }

    public void setEnabled(boolean enabled) {
        mConfig.enabled = enabled;
        persist();
    }

    public boolean isOpenSwitchEnabled() {
        return mConfig.openSwitchEnabled;
    }

    public void setOpenSwitchEnabled(boolean enabled) {
        mConfig.openSwitchEnabled = enabled;
        persist();
    }

    public boolean isCloseSwitchEnabled() {
        return mConfig.closeSwitchEnabled;
    }

    public void setCloseSwitchEnabled(boolean enabled) {
        mConfig.closeSwitchEnabled = enabled;
        persist();
    }

    private void restore() {
        mConfig = mDataStore.get(getId(), Config.class).get();
        if (mConfig == null) {
            mConfig = getDefaultConfig();
        }
    }

    protected abstract Config getDefaultConfig();

    private void persist() {
        mDataStore.put(getId(), mConfig);
    }

    public void setup(IOIO ioio) throws ConnectionLostException {
        mOutputPin = ioio.openDigitalOutput(mControlPinNumber, false);
        mClosedPin = ioio.openDigitalInput(mClosedPinNumber, DigitalInput.Spec.Mode.PULL_DOWN);
        mOpenPin = ioio.openDigitalInput(mOpenPinNumber, DigitalInput.Spec.Mode.PULL_DOWN);
    }

    private boolean mHasLatching;

    public void loop() throws ConnectionLostException, InterruptedException {
        // Holds the output closed so that other signals, even from non-PALADIN sources, can't be processed.
        // TODO incomplete feature, disabled for now...
        /*if (processLatching()) {
            return;
        }*/

        if (mActivatePin) {
            mActivatePin = false;
            // high for 2 seconds and then low again
            mOutputPin.write(true);
            Thread.sleep(TIME_PIN_ACTIVE_FOR_COMMAND);
            mOutputPin.write(false);
        } else {
            State state = readState();
            if (!mState.equals(state)) {
                confirm(state);
            }
        }
    }


    /**
     * Reads door state from open/close pins.
     * @return Door state
     */
    private State readState() throws ConnectionLostException, InterruptedException {
        if (isOpenSwitchEnabled() && isCloseSwitchEnabled()) {
            boolean isClosed = mClosedPin.read(); // true is closed
            boolean isOpen = mOpenPin.read(); // true is open
            Boolean triState = null;
            if (isClosed != isOpen) { // both pin LOW or HIGH means state is UNKNOWN
                triState = isOpen;
            }
            return State.from(triState);
        } else if (isCloseSwitchEnabled()) {
            return State.from(!mClosedPin.read()); // true is closed
        } else if (isOpenSwitchEnabled()) {
            return State.from(mOpenPin.read()); // true is open
        }
        return State.UNKNOWN;
    }

    private boolean latchingActive = false;

    private boolean processLatching() throws ConnectionLostException, InterruptedException {
        if (!this.isReady()) {
            //ToastManager.show("Latching scheduled but door not yet ready, waiting for the next loop...");
            Log.e(toString(), "Latching scheduled but door not yet ready, waiting for the next loop...");
            return false;
        }

        Schedule latchingSchedule = null;
        for (Schedule schedule : mScheduleManager.getAll()) {
            if (schedule.getDoors().contains(this.getId())) {
                if (schedule.isLatchingActive()) {
                    latchingSchedule = schedule;
                    break;
                }
            }
        }
        if (latchingSchedule == null) {
            //ToastManager.show("No latching schedule this loop");
            Log.e(toString(), "No latching schedule this loop");
            if (latchingActive) {
                //ToastManager.show("Disabling previous latching schedule");
                Log.e(toString(), "Disabling previous latching schedule");
                mOutputPin.write(false);
                latchingActive = false;
            }
            return false;
        }

        if (latchingSchedule.isLatchingInitialStateOpen() && readState() == State.OPEN) {
            //ToastManager.show("Closing door for latching schedule");
            mOutputPin.write(true);
            Thread.sleep(TIME_PIN_ACTIVE_FOR_COMMAND);
            mOutputPin.write(false);
            Thread.sleep(TIME_WAIT_FOR_DOOR_STATE);
            if (!isClosed()) {
                //ToastManager.show("Error closing door for latching schedule");
                Log.e(toString(), "Error closing door for latching schedule");
            } else {
                //ToastManager.show("Door closed for latching schedule");
                Log.e(toString(), "Door closed for latching schedule");
            }

        } else if (!latchingSchedule.isLatchingInitialStateOpen() && readState() == State.CLOSED) {
            //ToastManager.show("Opening door for latching schedule");
            mOutputPin.write(true);
            Thread.sleep(TIME_PIN_ACTIVE_FOR_COMMAND);
            mOutputPin.write(false);
            Thread.sleep(TIME_WAIT_FOR_DOOR_STATE);
            if (!isOpen()) {
                //ToastManager.show("Error opening door for latching schedule");
                Log.e(toString(), "Error opening door for latching schedule");
            } else {
                //ToastManager.show("Door opened for latching schedule");
                Log.e(toString(), "Door opened for latching schedule");
            }
        }

        mOutputPin.write(true);
        latchingActive = true;

        //ToastManager.show("Latching is active");
        Log.i(toString(), "Latching is active");

        return true;
    }

    @Override
    public String toString() {
        return getName();
    }

    @Singleton
    public static class One extends Door {
        @Inject
        private One() {
            super(1, 2, 4, 5);
        }

        @Subscribe
        public void on(CommandSent event) {
            super.on(event);
        }

        @Subscribe
        public void on(BoardConnected event) {
            super.on(event);
        }

        @Subscribe
        public void on(BoardDisconnected event) {
            super.on(event);
        }

        @Subscribe
        public void on(VoiceRecognizer.StateChanged event) {
            super.on(event);
        }

        @Override
        protected Config getDefaultConfig() {
            Config config = new Config();
            config.enabled = true;
            config.closeSwitchEnabled = true;
            config.openSwitchEnabled = false;
            config.name = "Door " + getId();
            config.openPhrase = GarD.instance.getString(R.string.default_open);
            config.closePhrase = GarD.instance.getString(R.string.default_close);
            return config;
        }

    }

    @Singleton
    public static class Two extends Door {
        @Inject
        private Two() {
            super(2, 3, 6, 7);
        }

        @Subscribe
        public void on(CommandSent event) {
            super.on(event);
        }

        @Subscribe
        public void on(BoardConnected event) {
            super.on(event);
        }

        @Subscribe
        public void on(BoardDisconnected event) {
            super.on(event);
        }

        @Subscribe
        public void on(VoiceRecognizer.StateChanged event) {
            super.on(event);
        }

        @Override
        protected Config getDefaultConfig() {
            Config config = new Config();
            config.enabled = false;
            config.closeSwitchEnabled = false;
            config.openSwitchEnabled = false;
            config.name = "Door " + getId();
            config.openPhrase = GarD.instance.getString(R.string.default_open);
            config.closePhrase = GarD.instance.getString(R.string.default_close);
            return config;
        }
    }

    public static class Config {
        public String name;
        public String openPhrase;
        public String closePhrase;
        public boolean enabled;
        public boolean openSwitchEnabled;
        public boolean closeSwitchEnabled;
    }

    public static abstract class Command {
        public final CommandSource source;
        public final String message;
        public final boolean forced;
        public final User user;

        public Command(CommandSource source, String message, boolean forced) {
            this(source, message, forced, null);
        }

        public Command(CommandSource source, String message, boolean forced, User user) {
            this.source = source;
            this.forced = forced;
            this.message = message;
            this.user = user;
        }

        protected abstract boolean apply(Door door);

        public abstract String toString();

    }

    public static class Open extends Command {

        public Open(CommandSource source, String message, boolean forced) {
            super(source, message, forced);
        }

        public Open(CommandSource source, String message, boolean forced, User user) {
            super(source, message, forced, user);
        }

        @Override
        protected boolean apply(Door door) {
            Log.i(toString(), "Command received with message: " + message);
            if (door.mPendingCommand != null && !forced) {
                Log.i(toString(), "Another command pending, ignored...");
                return false;
            }
            if (!door.isReady()) {
                Log.w(toString(), "Door not ready: " + door);
                ToastManager.show("The door is not ready.", 1);
                return false;
            }
            if (!door.isOpen()) {
                Log.i(toString(), "Opening door: " + door);
                Log.i(toString(), message);
                door.startAutoClose();
                Tattu.post(new CommandSent(door, this, message));
                return true;
            } else {
                Log.w(toString(), "Door already open: " + door);
                ToastManager.show("The door is already open.", 1);
                return false;
            }
        }

        @Override
        public String toString() {
            return "open";
        }
    }

    public static class Close extends Command {

        public Close(CommandSource source, String message, boolean forced) {
            super(source, message, forced);
        }

        public Close(CommandSource source, String message, boolean forced, User user) {
            super(source, message, forced, user);
        }

        @Override
        protected boolean apply(Door door) {
            if (!door.isReady()) {
                Log.w(toString(), "Door not ready: " + door);
                ToastManager.show("The door is not ready.", 1);
                return false;
            }
            if (door.isOpen()) {
                Log.i(toString(), "Closing door: " + door);
                Log.i(toString(), message);
                Tattu.post(new CommandSent(door, this, message));
                return true;
            } else {
                Log.w(toString(), "Door already closed: " + door);
                ToastManager.show("The door is already closed.", 1);
                return false;
            }
        }

        @Override
        public String toString() {
            return "close";
        }
    }

    public static class Toggle extends Command {

        public Toggle(CommandSource source, String message, boolean forced) {
            super(source, message, forced);
        }

        public Toggle(CommandSource source, String message, boolean forced, User user) {
            super(source, message, forced, user);
        }

        @Override
        protected boolean apply(Door door) {
            Log.i(toString(), "Toggle door: " + door);
            if (door.isOpen()) {
                return door.send(new Close(source, message, forced));
            } else if (door.isClosed()) {
                return door.send(new Open(source, message, forced));
            }
            return false;
        }

        @Override
        public String toString() {
            return "toggle";
        }
    }

    public enum State {
        OPEN("Open"), CLOSED("Closed"), UNKNOWN("Unknown");

        public final String description;

        State(String description) {
            this.description = description;
        }

        public static State from(Boolean opened) {
            return opened == null ? UNKNOWN : opened ? OPEN : CLOSED;
        }

        public Boolean toBoolean() {
            switch (this) {
                case OPEN:
                    return true;
                case CLOSED:
                    return false;
                case UNKNOWN:
                default:
                    return null;
            }
        }

        @Override
        public String toString() {
            return description;
        }
    }

    public Command getPendingCommand() {
        return mPendingCommand;
    }

    public boolean hasLatching() {
        return mHasLatching;
    }

}
