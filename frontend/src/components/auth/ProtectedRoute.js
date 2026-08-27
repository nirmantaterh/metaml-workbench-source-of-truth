import React from 'react'
import { useLocation, Outlet, Navigate} from 'react-router-dom';

const ProtectedRoute = ({ children, allowedRoles = [], useOutlet = false }) => {
    const isAuthenticated = localStorage.getItem("authToken");
    //const userRoles = JSON.parse(localStorage.getItem("userRoles")) || [];
    const location = useLocation();

    if (!isAuthenticated) {
       // Redirect to login and remember the last location add a path for '/login' that invokes the keycloak login (the code below is not going to work)
       return <Navigate to='/login' state={{ from: location }} replace />;
    } else {
      return useOutlet ? <Outlet /> : children;
    }

    // const userRolesLower = userRoles.map((role) => role.toLowerCase()); const allowedRolesLower = allowedRoles.map((role) => role.toLowerCase());

    // const isAuthorized = userRolesLower.some((userRole) => allowedRolesLower.includes(userRole) );

    // if (isAuthorized) { // Optionally render children or an Outlet based on useOutlet flag return useOutlet ? <Outlet /> : children; } else { // Redirect to a default or unauthorized access page if the user doesn't have an allowed role return <Navigate to='/unauthorized' state={{ from: location }} replace />; }
};

export default ProtectedRoute;