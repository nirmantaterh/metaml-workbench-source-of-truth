// jest-dom adds custom jest matchers for asserting on DOM nodes.
// allows you to do things like:
// expect(element).toHaveTextContent(/react/i)
// learn more: https://github.com/testing-library/jest-dom
import '@testing-library/jest-dom';

// react-router v7 touches TextEncoder/TextDecoder at import time, and the jsdom that ships with
// react-scripts 5 doesn't define either. Without this, importing anything from react-router-dom in
// a test throws "TextEncoder is not defined" before a single assertion runs. Node's own
// implementations are the same thing the browser gives us at runtime.
import { TextEncoder, TextDecoder } from 'util';

if (typeof global.TextEncoder === 'undefined') {
    global.TextEncoder = TextEncoder;
}
if (typeof global.TextDecoder === 'undefined') {
    global.TextDecoder = TextDecoder;
}
