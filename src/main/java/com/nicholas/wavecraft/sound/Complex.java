package com.nicholas.wavecraft.sound;

// Representa un número complejo (a + bi)
public class Complex {
    private final double re;   // la parte real
    private final double im;   // la parte imaginaria

    public Complex(double real, double imag) {
        re = real;
        im = imag;
    }

    // Suma de números complejos
    public Complex plus(Complex b) {
        return new Complex(this.re + b.re, this.im + b.im);
    }

    // Resta de números complejos
    public Complex minus(Complex b) {
        return new Complex(this.re - b.re, this.im - b.im);
    }

    // Multiplicación de números complejos
    public Complex times(Complex b) {
        double real = this.re * b.re - this.im * b.im;
        double imag = this.re * b.im + this.im * b.re;
        return new Complex(real, imag);
    }

    // Obtener la parte real
    public double re() {
        return re;
    }

    // Obtener la parte imaginaria
    public double im() {
        return im;
    }
}