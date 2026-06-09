package com.sapari.customer.port;

import com.sapari.customer.command.CustomerOAuthCommand;
import com.sapari.customer.result.CustomerOAuthResult;

public interface CustomerOAuthUseCase {

    CustomerOAuthResult handleOAuthSuccess(CustomerOAuthCommand command);
}
