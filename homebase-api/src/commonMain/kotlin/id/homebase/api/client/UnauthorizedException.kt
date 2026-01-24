package id.homebase.api.client

class UnauthorizedException :
    OdinApiException(401, "Unauthorized")