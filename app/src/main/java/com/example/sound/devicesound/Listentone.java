package com.example.sound.devicesound;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

import java.util.ArrayList;
import java.util.List;

import calsualcoding.reedsolomon.EncoderDecoder;
import google.zxing.common.reedsolomon.ReedSolomonException;

public class Listentone {

    int HANDSHAKE_START_HZ = 4096;
    int HANDSHAKE_END_HZ = 5120 + 1024;

    int START_HZ = 1024;
    int STEP_HZ = 256;
    int BITS = 4;

    int FEC_BYTES = 4;

    private int mAudioSource = MediaRecorder.AudioSource.MIC;
    private int mSampleRate = 44100;
    private int mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private float interval = 0.1f;
    private EncoderDecoder encoderDecoder;
    private int mBufferSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioFormat);

    public AudioRecord mAudioRecord;
    int audioEncodig;
    boolean startFlag;
    FastFourierTransformer transform;


    public Listentone(){
        transform = new FastFourierTransformer(DftNormalization.STANDARD);
        startFlag = false;
        mAudioRecord = new AudioRecord(mAudioSource, mSampleRate, mChannelCount, mAudioFormat, mBufferSize);
        mAudioRecord.startRecording();
        encoderDecoder = new EncoderDecoder();
    }

    public void PreRequest() {
        int blocksize = ((int)(long)(interval/2*mSampleRate));
        short[] buffer = new short[blocksize];
        double[] chunk = new double[findPowerSize((int)(long)Math.round(interval/2*mSampleRate))];
        byte[] byte_stream;
        List<Double> packet = new ArrayList<>();
        while(true) {
            int bufferedReadResult = mAudioRecord.read(buffer, 0, blocksize);
            if(bufferedReadResult < 0) continue;

            for(int i = 0; i< chunk.length; i++){                      //zeropadding
                if(i < buffer.length) chunk[i] = (double) buffer[i];
                else chunk[i] = 0;
            }
            double dom = findFrequency(chunk);

            if(startFlag && match(dom,HANDSHAKE_END_HZ)) {
                byte_stream = extract_packet(packet);
                Log.d("ListenTone packet :", String.valueOf(packet));
                Log.d("ListenTone bytestream :", String.valueOf(byte_stream));

                try {
                    encoderDecoder.decodeData(byte_stream,FEC_BYTES);
                    String result = "";
                    for (int i = 0; i < byte_stream.length; i++) {
                        result += Character.toString((char) ((int) byte_stream[i]));
                    }
                    Log.d("ListenTone result :", result);
                } catch (ReedSolomonException e) {
                } catch (EncoderDecoder.DataTooLargeException e) {
                }

                packet.clear();
                startFlag=false;

            }else if (startFlag) {
                packet.add(dom);
                Log.d("ListenTone Freq", String.valueOf(dom));
            }
            else if (match(dom,HANDSHAKE_START_HZ))
                startFlag = true;
        }

    }

    private boolean match(double freq1, int freq2) {
        return Math.abs(freq1 - freq2) < 40;
    }

    private int findPowerSize(int value) {
        int square = 2;
        while(true){
            if(value < square){                 //가장 가까운 2제곱을 return
                return ((square + square/2)/2 > value)? square/2 : square;
            }
            square *= 2;
        }
    }

    private double findFrequency(double[] toTransform) {
        int len = findPowerSize(toTransform.length);
        double realNum;
        double imgNum;
        double[] mag = new double[len];

        Complex[] complx = transform.transform(toTransform,TransformType.FORWARD); //1차원 푸리에 연산을 통한 복소수값들
        Double[] freq = this.fftfreq(complx.length,1);    //sampling된  freq들

        for (int i = 0; i < complx.length; i++){
            realNum = complx[i].getReal();                               //실수 부분
            imgNum = complx[i].getImaginary();                           //허수 부분
            mag[i] = Math.sqrt((realNum * realNum) + (imgNum * imgNum)); //복소수의 크기를 구해줌
        }

        int peak_coeff = 0;
        double peak = Math.abs(mag[0]);
        for(int i = 1; i < complx.length; i++){        //np.argmax의 구현
            if(peak < Math.abs(mag[i])){
                peak = mag[i];
                peak_coeff = i;
            }
        }
        double peak_freq = freq[peak_coeff];
        return Math.abs(peak_freq * mSampleRate);
    }

    private Double[] fftfreq(int n, int d) {
        double val = 1.0 / (n * d);
        Double[] results = new Double[n];
        int N = (n - 1)/ 2 + 1;
        int[] p = new int[n];
        for(int i = 0; i < N; i++) {
            p[i] = i;
        }
        for(int i = N, j = -(n/2); i < n; i++,j++){
            p[i] = j;
        }
        for(int i = 0; i < n; i++){
            results[i] = p[i] * val;
        }
        return results;
    }

    private byte[] extract_packet(List<Double> freqs) {

        List<Double> _freqs = new ArrayList<>();
        List<Integer> bit_chunks = new ArrayList<>();
        List<Integer> chunks = new ArrayList<>();

        for(int i = 0; i < freqs.size(); i= i+2){
            double f = freqs.get(i);
            _freqs.add(f);
        }
        for(int i = 0; i < _freqs.size(); i++){
            int f = (int)(Math.round((_freqs.get(i) - START_HZ)/STEP_HZ));
            bit_chunks.add(f);
        }
        for(int i=1; i < bit_chunks.size(); i++) {
            if (bit_chunks.get(i) >= 0 && bit_chunks.get(i) < 16) {
                chunks.add(bit_chunks.get(i));
            }
        }
        Log.d("ListenTone bits", String.valueOf(chunks));
        byte[] result = decodeBitchunks(BITS, chunks);
        return result;
    }

    private byte[] decodeBitchunks(int bits, List<Integer> chunks) {

        List<Integer> out_bytes = new ArrayList<>();
        int next_read_chunk = 0;
        int next_read_bit = 0;

        int _byte = 0;

        int bits_left = 8;

        while(next_read_chunk < chunks.size()) {
            int can_fill = bits - next_read_bit;
            int to_fill = Math.min(bits_left, can_fill);
            int offset = bits - next_read_bit - to_fill;

            _byte <<= to_fill;
            int shifted = chunks.get(next_read_chunk) & (((1 << to_fill) - 1) << offset);
            _byte |= shifted >> offset;
            bits_left -= to_fill;
            next_read_bit += to_fill;

            if(bits_left <= 0 ){
                out_bytes.add(_byte);
                _byte = 0;
                bits_left = 8;
            }
            if(next_read_bit >= bits){
                next_read_chunk += 1;
                next_read_bit -= bits;
            }
        }
        Log.d("ListenTone chuncks", String.valueOf(out_bytes));
        byte[] _out_bytes = new byte[out_bytes.size()];
        for(int i = 0; i < _out_bytes.length; i++)
            _out_bytes[i] = (out_bytes.get(i)).byteValue();
        return _out_bytes;
    }

}
