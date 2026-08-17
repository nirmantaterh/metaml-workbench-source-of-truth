import { useEffect, useState } from "react";
import { format } from "date-fns";

export const useAlertWithTimeout = (
    initialVisibility = false,
    duration = 10000
) => {
    const [isVisible, setIsVisible] = useState(initialVisibility);

    useEffect(() => {
        let timer;
        if (isVisible) {
            timer = setTimeout(() => {
                setIsVisible(false);
            }, duration);
        }
        return () => clearTimeout(timer);
    }, [isVisible, duration]);

    return [isVisible, setIsVisible];
};

// Deterministic color from a string, via a simple string hash mapped to a hue.
export const generateColor = (str) => {
    if (typeof str !== "string" || str.length === 0) {
        return "#8884d8";
    }

    let hash = 0;
    for (let i = 0; i < str.length; i++) {
        hash = str.charCodeAt(i) + ((hash << 5) - hash);
    }

    const hue = hash % 360;
    return `hsl(${hue}, 70%, 50%)`;
};

export const dateTimeFormatter = (date, time) => {
    const formattedDate = format(date, "yyyy-MM-dd");
    const formattedTime = format(time, "HH:mm");
    return { formattedDate, formattedTime };
};

export const formatAppointmentStatus = (status) => {
    return status.toLowerCase().replace(/_/g, "-");
}

export const UserType = {
    PATIENT: "PATIENT",
    HP: "HP",
}