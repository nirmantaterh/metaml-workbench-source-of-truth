import React from "react";

const NoDataAvailable = ({ dataType, errorMessage }) => {
    return (
        <div className='text-center mt-5'>
            <h5>No {dataType} available at the moment.</h5>
            {errorMessage && <p className='text-danger'>{errorMessage}</p>}
        </div>
    );
};

export default NoDataAvailable;