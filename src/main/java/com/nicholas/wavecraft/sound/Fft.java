package com.nicholas.wavecraft.sound;

// Implementación del algoritmo FFT de Cooley-Tukey
public class Fft {

    // Calcula la FFT
    public static Complex[] fft(Complex[] x) {
        int n = x.length;

        // Caso base
        if (n == 1) return new Complex[]{x[0]};

        // La longitud debe ser potencia de 2
        if (Integer.highestOneBit(n) != n) {
            throw new IllegalArgumentException("La longitud del array no es una potencia de 2");
        }

        // FFT de las mitades par e impar
        Complex[] even = new Complex[n / 2];
        Complex[] odd = new Complex[n / 2];
        for (int k = 0; k < n / 2; k++) {
            even[k] = x[2 * k];
            odd[k] = x[2 * k + 1];
        }
        Complex[] q = fft(even);
        Complex[] r = fft(odd);

        // Combinar
        Complex[] y = new Complex[n];
        for (int k = 0; k < n / 2; k++) {
            double kth = -2 * k * Math.PI / n;
            Complex wk = new Complex(Math.cos(kth), Math.sin(kth));
            y[k] = q[k].plus(wk.times(r[k]));
            y[k + n / 2] = q[k].minus(wk.times(r[k]));
        }
        return y;
    }

    // Calcula la Inversa de la FFT
    public static Complex[] ifft(Complex[] x) {
        int n = x.length;
        Complex[] y = new Complex[n];

        // Tomar el conjugado
        for (int i = 0; i < n; i++) {
            y[i] = new Complex(x[i].re(), -x[i].im());
        }

        // Calcular la FFT
        y = fft(y);

        // Tomar el conjugado de nuevo y escalar
        for (int i = 0; i < n; i++) {
            y[i] = new Complex(y[i].re() / n, -y[i].im() / n);
        }

        return y;
    }
}