package dev.atrium.link;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

// The phone's sound taken by a dynamic audio policy: a loopback mix claims
// every player with one of USAGES before Android routes it, so nothing
// plays on the phone, and the mix is recorded. Unlike recording
// REMOTE_SUBMIX (the default output), this holds when an app goes into a
// voice call's mode: PUBG with its mic on puts the phone in
// MODE_IN_COMMUNICATION, where media and voice chat both go to the speaker.
// Shell holds MODIFY_AUDIO_ROUTING and CAPTURE_VOICE_COMMUNICATION_OUTPUT;
// the classes are system API, hence reflection. Not "privileged" capture,
// which would also take apps that opt out of capture: that is held to 16 kHz. Unregistering, or the
// process dying, gives the sound back.
final class Loopback {
    // Ringtones and alarms stay on the phone, as they did with REMOTE_SUBMIX.
    private static final int[] USAGES = {
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
            AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_EVENT,
    };
    private static final int RULE_MATCH_ATTRIBUTE_USAGE = 1;
    private static final int ROUTE_FLAG_LOOP_BACK = 2;

    private final AudioManager audio;
    private final Object policy;
    final AudioRecord record;

    private Loopback(AudioManager audio, Object policy, AudioRecord record) {
        this.audio = audio;
        this.policy = policy;
        this.record = record;
    }

    // Throws when the policy can't be made or registered.
    static Loopback open(Context ctx, int rate) throws Exception {
        Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object rule = ruleBuilderClass.getConstructor().newInstance();
        Method addMixRule = ruleBuilderClass.getMethod("addMixRule", int.class, Object.class);
        for (int usage : USAGES)
            addMixRule.invoke(rule, RULE_MATCH_ATTRIBUTE_USAGE, new AudioAttributes.Builder().setUsage(usage).build());
        ruleBuilderClass.getMethod("voiceCommunicationCaptureAllowed", boolean.class).invoke(rule, true);
        Object mixingRule = ruleBuilderClass.getMethod("build").invoke(rule);

        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Constructor<?> mixCtor = mixBuilderClass.getConstructor(Class.forName("android.media.audiopolicy.AudioMixingRule"));
        Object mixBuilder = mixCtor.newInstance(mixingRule);
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
        mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK);
        Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(ctx);
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
        Object policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);

        Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        AudioManager audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int r = (int) AudioManager.class.getMethod("registerAudioPolicy", policyClass).invoke(audio, policy);
        if (r != 0)
            throw new IllegalStateException("registerAudioPolicy: " + r);
        try {
            AudioRecord record = (AudioRecord) policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix);
            if (record == null)
                throw new IllegalStateException("no record sink");
            return new Loopback(audio, policy, record);
        } catch (Exception e) {
            unregister(audio, policy);
            throw e;
        }
    }

    void close() {
        try {
            record.stop();
        } catch (IllegalStateException ignored) {
        }
        record.release();
        unregister(audio, policy);
    }

    private static void unregister(AudioManager audio, Object policy) {
        try {
            AudioManager.class.getMethod("unregisterAudioPolicy", policy.getClass()).invoke(audio, policy);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.i("audio: unregistering the policy: " + e);
        }
    }
}
