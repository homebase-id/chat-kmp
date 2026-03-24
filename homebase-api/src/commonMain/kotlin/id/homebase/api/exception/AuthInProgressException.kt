package id.homebase.api.exception

class AuthInProgressException: Exception("Authentication is already in progress. Please wait for the current authentication process to complete before starting a new one.")