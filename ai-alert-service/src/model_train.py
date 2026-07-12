import numpy as np
import tensorflow as tf
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import LSTM, Dense
from tensorflow.keras.callbacks import EarlyStopping

from .zscore import create_dataset


def build_model(input_shape, units: int):
    model = Sequential()
    model.add(LSTM(units, input_shape=input_shape))
    model.add(Dense(input_shape[-1]))
    model.compile(optimizer='adam', loss='mse')
    return model


def train_model(series: np.ndarray, window: int, units: int, epochs: int, batch_size: int):
    X, y = create_dataset(series, window)
    if len(X) == 0:
        return None
    model = build_model((X.shape[1], X.shape[2]), units)
    model.fit(
        X,
        y,
        epochs=epochs,
        batch_size=batch_size,
        validation_split=0.1,
        callbacks=[EarlyStopping(patience=3, restore_best_weights=True)],
        verbose=0,
    )
    return model
