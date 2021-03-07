package com.cooper.wheellogwatchservice;

interface IWatchInterface {
        void sendData(in String[] data);
        boolean isConnected();
        List<String> getErrors();
}