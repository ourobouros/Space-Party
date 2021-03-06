package com.dougwoos.spaceparty_receiver.spaceparty_receiver;

import android.annotation.TargetApi;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;



public class Listener {
    // buffer size must be <= recorder buffer size
    static byte[] string_to_bits(String s){
        byte[] bits = new byte[s.length()*8];
        for(int i = 0; i < s.length(); ++i){
            char c = s.charAt(i);
            for(int j = 0; j < 8; ++j){
                bits[i*8+j] = (byte)((c>>(7-j))&1);
            }
        }
        return bits;
    }

    public static int TRANSMIT_HZ = 44100;
    private static final int RECORDER_BUFFER_SIZE = 5*44100;

    static String secret = "UaU";
    static byte[] secret_bits = string_to_bits(secret);


    private static final int SAMPLES_PER_BIT = 36;
    private static final int BYTES_PER_READ = Math.max(3*secret_bits.length/8, 5*4);
    private static final int BITS_PER_READ = BYTES_PER_READ*8;
    private static final int BITS_PER_BUFF = BITS_PER_READ*3;

    private static final int BUFFER_SIZE = SAMPLES_PER_BIT*BITS_PER_BUFF;
    private static final int READ_SIZE = SAMPLES_PER_BIT*BITS_PER_READ;

    private static final int MARK_HZ = 1200;
    private static final int SPACE_HZ = 2200;
    int read_left = -1;

    private static final float MARK_CROSS = 2.0f*MARK_HZ*SAMPLES_PER_BIT/TRANSMIT_HZ;
    private static final float SPACE_CROSS = 2.0f*SPACE_HZ*SAMPLES_PER_BIT/TRANSMIT_HZ;


    public short[] buffer = new short[BUFFER_SIZE];
    private byte[] decode = new byte[BITS_PER_READ + secret_bits.length];
    private byte[] received_bytes;

    float WAVES_PER_BIT = Math.max(MARK_CROSS, SPACE_CROSS);
    float SAMPLES_PER_WAVE = SAMPLES_PER_BIT/WAVES_PER_BIT;
    int INCR = (int) SAMPLES_PER_WAVE/4;

    private int index = 0;
    private int read_index = -1;
    int[] lens = new int[5];


    public byte[] random_bytes(int len){
        Random r = new Random(0);
        byte[] bytes = new byte[len];
        r.nextBytes(bytes);
        return bytes;
    }

    public int random_length = 1000;
    public byte[] random_bytes = random_bytes(random_length);


    AudioRecord recorder;
    MainActivity main;

    public Listener(MainActivity m) {
        main = m;
        Log.v("initializing listener", "wooooo");
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                                   TRANSMIT_HZ, AudioFormat.CHANNEL_IN_MONO,
                                   AudioFormat.ENCODING_PCM_16BIT,
                                   Math.max(RECORDER_BUFFER_SIZE,
                                            AudioRecord.getMinBufferSize(TRANSMIT_HZ, AudioFormat.CHANNEL_IN_MONO,
                                                                         AudioFormat.ENCODING_PCM_16BIT)));
    }

    void find_secret(){

        read_left = -1;
        int sum = 0;
        int count = 0;

        for(int offset = 0; offset < SAMPLES_PER_BIT; offset += 2){
            int start_index = index - decode.length*SAMPLES_PER_BIT + offset;
            for(int i = 0; i < decode.length; ++i){
                decode[i] = decode(start_index+i*SAMPLES_PER_BIT);
            }

            for(int i = 0; i < decode.length - secret_bits.length; ++i){
                int match = 1;
                for(int j = 0; j < secret_bits.length; ++j){
                    if(decode[i+j] != secret_bits[j]){
                        match = 0;
                        break;
                    }
                }
                if (match==1){
                    int next = i*SAMPLES_PER_BIT + start_index;
                    if(count == 0 || Math.abs(sum/count-next) < SAMPLES_PER_BIT){
                        sum += next;
                        ++count;
                    }
                }
            }
        }
        if(count > 0) {
            Log.v("Count:", String.valueOf(count));
            read_index = (sum/count + secret_bits.length*SAMPLES_PER_BIT + BUFFER_SIZE)%BUFFER_SIZE;
        }
    }

    void read_header(){
        for(int k = 0 ; k < 5; ++k){
            String s = "";
            for(int i = 0; i < 8*4; ++i){
                short bit = decode(read_index);
                read_index += SAMPLES_PER_BIT;
                if(bit == 1)s += "1";
                else s +="0";
            }
            if(s.charAt(0) == '1'){
                Log.v("Rejecting", "Malformed or Too Long!");
            }else{
                lens[k] = Integer.parseInt(s, 2);
                Log.v("Length", String.valueOf(lens[k]));
            }
            Log.v("Bits", s);
        }

        Arrays.sort(lens);
        read_left = lens[2];
        Log.v("lengths:", Arrays.toString(lens));

        Log.v("Message Length", String.valueOf(read_left));
        received_bytes = new byte[read_left];
    }

    void read_byte(){
        int byte_index = received_bytes.length - read_left;
        byte b = 0;
        for(int i = 0; i < 8; ++i){
            short bit = decode(read_index);
            read_index = (read_index+SAMPLES_PER_BIT)%buffer.length;
            if(bit == 1) b = (byte)(b|1<<(7-i));
        }
        received_bytes[byte_index] = b;
        --read_left;
    }

    void read_message(){
        int count = 0;
        while(count < BYTES_PER_READ && read_left > 0){
            read_byte();
            ++count;
        }
        if(read_left == 0){
            read_index = -1;
            if(received_bytes.length == random_length){
                int errors = 0;
                for(int i = 0; i < random_length; ++i){
                    int diff = ((received_bytes[i]&0xff)^(random_bytes[i]&0xff));
                    errors += Integer.bitCount(diff);
                }
                Log.v("Error Rate:", String.format("%f",errors/(random_length*8.)));
            }else{
                Log.v("Message!!:", new String(received_bytes));
                main.setText(new String(received_bytes));
            }
        }
    }
    /*
Random r = new Random();
    private void poll() {
        //recorder.read(buffer, index, READ_SIZE);
        index = (index + READ_SIZE)%buffer.length;

        short[] wave = send_message("Hello".getBytes());
        int offset = Math.abs(r.nextInt())%(buffer.length - wave.length);

        if(read_index < 0){
            index = offset+READ_SIZE+2000;
            System.arraycopy(wave, 0, buffer, offset, wave.length);
            find_secret();
            if(read_index < 0) Log.v("Offset", String.valueOf(offset));
        }
        else if(read_left <= 0) read_header();
        else read_message();


        //drawView.postInvalidate();
    }*/
    private void poll() {
        recorder.read(buffer, index, READ_SIZE);
        index = (index + READ_SIZE)%buffer.length;

        if(read_index < 0) find_secret();
        else if(read_left <= 0) read_header();
        else read_message();
    }

int log = 0;

    private byte decode(int index)
    {
        int count = 0;

        //Log.v("ing", String.valueOf(incr));
        for(int i = index; i < index+SAMPLES_PER_BIT; i += INCR){
            if((buffer[(i-INCR+2*buffer.length)%buffer.length]<0) != (buffer[(i+2*buffer.length)%buffer.length]<0)) ++count;
        }

        //Log.v("Crossings", String.valueOf(count));

        if(Math.abs(count-MARK_CROSS) < Math.abs(count-SPACE_CROSS)){
            return 1;
        }else{
            return 0;
        }
    }

    public void listen(DrawView drawView) {
            Log.v("Mark cross", String.valueOf(MARK_CROSS));
            Log.v("Space cross", String.valueOf(SPACE_CROSS));
            Log.v("Secret bits", String.valueOf(secret_bits.length));
            Log.v("BPR", String.valueOf(BITS_PER_READ));
            drawView.setShorts(buffer);

            int preambleIndex = -1;
            recorder.startRecording();
            while (preambleIndex < 0 || true) {
                if(Thread.interrupted()) {
                    Log.v("interrupted", "exiting");
                    recorder.stop();
                    recorder.release();
                    return;
                }
                // spin while looking for the preamble
                poll();
                drawView.postInvalidate();

                //preambleIndex = findPreamble();
                //Log.v("Found Preamble", String.valueOf(preambleIndex));
            }
            // cool, now we have a transmission.
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public byte[] encode(byte[] message){
        byte[] data = new byte[secret.length() + message.length + 4];
        ByteBuffer b = ByteBuffer.allocate(4);
        b.putInt(message.length);
        System.arraycopy(secret.getBytes(), 0, data, 0, secret.length());
        System.arraycopy(b.array(), 0, data, secret.length(), b.array().length);
        System.arraycopy(message, 0, data, 4+secret.length(), message.length);
        return data;
    }

    public short[] bell202_modulate(byte[] data){
        double mark_hz = 1200;
        double space_hz = 2400;

        short[] wave = new short[data.length*8*SAMPLES_PER_BIT];
        int count = 0;

        double t = 0;
        for(int i = 0; i < data.length; ++i){
            byte b = data[i];
            for(int j = 7; j >= 0; --j){
                int bit = (b >> j) & 1;
                for(int k = 0; k < SAMPLES_PER_BIT; ++k){
                    double step = 1./TRANSMIT_HZ;
                    if(bit == 1) step *= mark_hz;
                    else step *= space_hz;
                    t += step;
                    wave[count++] = (short)(Math.sin(t*Math.PI*2)*Short.MAX_VALUE);
                }
            }
        }
        return wave;
    }
    short[] send_message(byte[] s){
        byte code[] = encode(s);
        short wave[] = bell202_modulate(code);
        return wave;
    }


}
